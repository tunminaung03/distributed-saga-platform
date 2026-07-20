# 💳 Financial Ledger Settlement & Compensation Engine (`payment-service`)

[⬅️ Back to Master README](../README.md)

The `payment-service` serves as the financial checkpoint for the Distributed Saga ecosystem. Operating via **Spring Cloud Stream Kafka**, the service processes incoming order transactions, deducts user balances, writes to an internal ledger, and triggers compensating financial rollbacks (`REFUNDED`) if a downstream kitchen warehouse inventory allocation fails.

---

## 🚀 Core Functional Responsibilities
* **Ledger Validation Master**: Subscribes to the `order-created-topic` and acts as the immediate financial checkpoint before item reservation happens.
* **Proactive Double-Billing Shield**: Uses an `Optional` repository check (`findByOrderId`) inside the core payment flow to skip duplicate processing and prevent double-charging users.
* **Resilient Compensation Handler**: Intercepts downstream kitchen rejections (`RestaurantRejectedEvent`) to reverse payments safely via database-backed state updates.
* **Trace-Stamped Outbox Broker**: Captures open OpenTelemetry tracing contexts at the point of storage, saving the `traceId` and `spanId` directly to the `OutboxRecord` matrix to prevent log fragmentation.

---

## 🛠️ Technology Stack & Runtime Profile
* **Runtime Environment**: Java 25 Engine (Eclipse Temurin Alpine JRE)
* **Core Framework**: Spring Boot 4.0.6 & Spring Cloud Stream Kafka Binder
* **Messaging Broker Engine**: Apache Kafka Core Protocol (v3.x KRaft topology)
* **Telemetry Core**: Micrometer Tracing Bridge with native OpenTelemetry backends
* **Database Platform Engine**: Shared PostgreSQL schemas / In-Memory H2 dialers

---

## 🎛️ Architecture & Message Processing Topology

```text
                        [ order-created-topic ]
                                   │
                     (W3C Propagation Context Header)
                                   ▼
                    [ processPayment Inbound Bean ]
                 (OpenTelemetry CONSUMER Span Context)
                                   │
                      ☕ Virtual Thread Execution
                                   │
                      ⚠️ Validate Inbound PENDING Status
                                   │
                      🛡️ Proactive Idempotency Guard
                                   ▼
                        💵 deductFunds Processing
                                   │
                  💾 Save Payment Record (APPROVED)
                                   │
             💾 OutboxRecord Table + Capture Trace/Span ID
                                   │
                PESSIMISTIC_WRITE Lock with SKIP LOCKED Loop
                                   ▼
             ⚙️ OutboxPublisherService Scheduled Loop (500ms)
                                   │
                                   ▼
                      [ payment-processed-topic ]
                                   │
                                   ▼
                         [ restaurant-service ]
                                   │
                 ┌─────────────────┴─────────────────┐
                 ▼ (Success Path)                    ▼ (Rejection Path)
    [ restaurant-approved-topic ]       [ restaurant-rejected-topic ]
                 │                                   │
                 ▼                                   ▼
       [ order-service ]                  [ payment-service ]
                 │                    (compensatePayment Inbound Bean)
                 ▼                                   │
       Marks Order SUCCESS                           ▼
                                         🛡️ Refund Idempotency Guard
                                                     ▼
                                          💾 Save Record (REFUNDED)
```

---

## ⚙️ Core Saga Financial State Control

The service acts as an event-driven worker across twin entry channels. It uses an internal `PaymentConsumerConfig` class to intercept inbound messages and mount them onto an active OpenTelemetry `CONSUMER` span before calling the transaction layers.

```java
private void executeInActiveContext(String spanName, Runnable businessLogic) {
    Tracer tracer = openTelemetry.getTracer(tracerScopeName);
    Span consumerSpan = tracer.spanBuilder(spanName)
            .setParent(Context.current()) // Anchors onto incoming traceparent context
            .setSpanKind(SpanKind.CONSUMER)
            .startSpan();
    try (Scope scope = consumerSpan.makeCurrent()) {
        businessLogic.run();
    } catch (Exception e) {
        consumerSpan.recordException(e);
        throw e;
    } finally {
        consumerSpan.end();
    }
}
```

### Async Consumer Beans
* **🟢 Transaction Process Ingress (`processPayment`)**: 
  * **Stream Source**: Pulls payloads from `order-created-topic`.
  * **Tracer Identifier**: Mounts the span `consume_order_created_event`.
  * **State Constraints**: Validates that the order status is explicitly `PENDING`. If a non-pending event is received, it throws an `IllegalArgumentException`.
  * **Action**: Calls `deductFunds()` to save an `APPROVED` record and stages a `PaymentProcessed` item in the transaction outbox.
* **🔴 Reverse Compensation Ingress (`compensatePayment`)**: 
  * **Stream Source**: Pulls payloads from `restaurant-rejected-topic`.
  * **Tracer Identifier**: Mounts the span `consume_restaurant_rejected_event`.
  * **Action**: Fetches the existing payment record and verifies it is currently `APPROVED`. If it is already refunded, it skips processing to handle network retries smoothly.
  * **State Shift**: Updates the payment status from `APPROVED` to `REFUNDED`.

---

## 📦 Transactional Outbox & Concurrency Controls

The engine decouples database updates from network messaging to prevent inconsistent state bugs if the database commits but Kafka drops the connection. 

### 1. High-Concurrency Row Lock Bypass
The engine prevents cluster nodes from conflicting over the same outbox items by setting specific persistence query boundaries inside the `OutboxRepository`:

```java
public interface OutboxRepository extends JpaRepository<OutboxRecord, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<OutboxRecord> findByOrderByCreatedTimeAsc(Pageable pageable);
}
```
* **`PESSIMISTIC_WRITE`**: Places an exclusive row-level lock on pulled rows to prevent concurrent balance mutations.
* **Timeout Hint (`-2`)**: Instructs Hibernate to append `FOR UPDATE SKIP LOCKED` to the compiled SQL string. Nodes silently skip currently locked rows and process the next free entries.

### 2. Scheduled Outbox Publishing Loop
The `OutboxPublisherService` runs continuously on an automated background loop to clear pending entries:
* **Poller Target**: Queries the database repository every 500ms using a configured batch chunk limit.
* **Message Assembly**: Converts outbox JSON strings back into concrete Java payloads and sets up a standard Spring `Message` containing the original order ID as the partition key.
* **Atomic Deletion**: Calls `streamBridge.send()` to push the record to `payment-processed-topic`. The database row is safely deleted only after a successful broker acknowledgment.

---

## 📊 Filtered Telemetry & Operational Noise Isolation

The service pushes performance data out of the container using segmented transmission channels. To remain compliant with the strict **5.5GB cluster limit**, it deploys an `ObservationPredicate` bean alongside a custom Logback layout to strip out routine pipeline noise.

### 1. Dual-Protocol Telemetry Matrix
```yaml
management:
  otlp:
    metrics:
      export:
        url: "http://\${OTEL_COLLECTOR_HOST:otel-collector.monitoring}:4318/v1/metrics"  # HTTP REST route
  opentelemetry:
    tracing:
      export:
        otlp:
          endpoint: "http://\${OTEL_COLLECTOR_HOST:otel-collector.monitoring}:4317"       # gRPC stream channel
          transport: grpc
    logging:
      export:
        otlp:
          endpoint: "http://\${OTEL_COLLECTOR_HOST:otel-collector.monitoring}:4317"       # gRPC stream channel
          transport: grpc
```

### 2. Core Instrumentation Filters
* **W3C `traceparent` Generation**: Formats tracing states into the standard layout `00-[traceId]-[spanId]-01` and injects it into outbound message frames. This links parent runtime events to downstream consumers without breaking distributed traces.
* **Background Task Telemetry Suppression**: The custom `ObservationPredicate` bean completely strips out observation metrics for the `publishNextPaymentOutboxBatch` background poller loop executed by the `OutboxPublisherService` every 500ms.
* **Actuator Noise Suppression**: The tracking loop drops performance metrics for monitoring routes matching `/actuator/health` or `/actuator/info` by inspecting the servlet-based `.getCarrier().getRequestURI()` path extractor.

### 3. Custom Logback Event Suppression
The service registers a custom `MessageContainsFilter` inside its `logback-spring.xml` configuration to prevent transactional outbox database polling dumps from flooding stdout or filling the OTLP log streams to Grafana Loki:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <filter class="dev.tunmin.saga.paymentservice.config.logging.MessageContainsFilter">
            <keywords>outbox_record</keywords>
        </filter>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-}, %X{spanId:-}] [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <filter class="dev.tunmin.saga.paymentservice.config.logging.MessageContainsFilter">
            <keywords>outbox_record</keywords>
        </filter>
    </appender>
</configuration>
```

### 4. Micrometer Offset Lag Metric Drop
The engine completely excludes heavy partition offset tracking data from Prometheus scraping collectors via an explicit deny filter to preserve memory limits under the 5.5GB threshold:

```java
@Bean
MeterFilter disableKafkaLagMetrics() {
    return MeterFilter.denyNameStartsWith(KafkaBinderMetrics.OFFSET_LAG_METRIC_NAME);
}
```

### 5. Active Tracing Context Reconstruct Loop
When emitting events, the `OutboxPublisherService` background worker extracts the raw `traceId` and `spanId` strings from the database `OutboxRecord`. It passes them to Micrometer's context builder to reconstruct the W3C trace parent headers (`00-[trace_id]-[span_id]-01`) dynamically, ensuring continuous distributed span visibility across Kafka boundaries.

### 6. Background Task Telemetry Suppression
Because the transactional outbox pipeline runs continuously every 500ms, its scheduled execution metrics would flood your telemetry storage. The service deploys a custom `ObservationPredicate` bean to strip out this polling noise:

* **Scheduled Task Exclusion**: The bean captures execution frames named `tasks.scheduled.execution` and completely blocks the `publishNextPaymentOutboxBatch` scheduled task from generating observation metrics.
* **Actuator Path Filtering**: It scans inbound servlet operations via `ServerRequestObservationContext` and strips tracking data for endpoints matching `/actuator/health` or `/actuator/info` using the servlet-based `.getCarrier().getRequestURI()` path extractor.
