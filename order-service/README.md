# 📑 Distributed Saga Transaction State Machine Orchestrator Engine (`order-service`)

[⬅️ Back to Master README](../README.md)

The `order-service` acts as the brain and core transactional coordinator for the Distributed Saga ecosystem. Operating via **Spring WebMVC** and **Spring Cloud Stream Kafka**, this microservice processes initial client order requests, initiates asynchronous Saga workflows, manages state machine updates, and handles transaction completion or compensation paths based on downstream network events.


---

## 🚀 Core Functional Responsibilities
* **Saga Transaction Coordinator**: Initializes the asynchronous transaction lifecycle by tracking orders via a `PENDING` state and publishing structural events to Kafka.
* **Perimeter Identity Protection**: Prevents cross-tenant identity spoofing by matching the gateway-injected `X-Perimeter-User-Id` header directly against the request payload (`ClientIdentityMismatchException`).
* **Multi-Tiered Idempotency Checkpoints**: Employs an explicit dual-stage verification check (combining pre-load `existsByIdAndStatus` scanning with post-load state analysis) to safely reject duplicate messages from Kafka.
* **W3C Distributed Trace Re-Anchoring**: Extracts tracer information during transactions and injects them as W3C `traceparent` headers into Kafka messages to guarantee end-to-end trace visibility across services.
* **High-Performance Transactional Outbox**: Leverages database-staged events combined with a `SKIP LOCKED` row-level lock bypass to decouple business operations from the messaging plane.

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
                        [ Inbound Client Traffic ]
                                    │
                          (Host Port: 30080)
                                    ▼
                 [ api-gateway-service (Netty/WebFlux) ]
                                    │
           🟢 Sidecarless Cilium eBPF Kernel-Level L7 Routing Matrix
                                    │
           1. Create Order          ▼
       ┌─────────────────── [ order-service ] ◄───────────────────────────────────┐
       │                            │                                             │
       │                            ▼ (Asynchronous Kafka Pipeline)               │
       │                  [ order-created-topic ]                                 │
       │                            │                                             │
       │                            ▼                                             │
       │                    [ payment-service ] ◄──────────────┐                  │
       │                            │                          │                  │
       │                            ▼ (Settles Balance)        │                  │
       │                  [ payment-processed-topic ]          │                  │
       │                            │                          │                  │
       │                            ▼                          │                  │
       │                   [ restaurant-service ] ────┐        │                  │
       │                                              │        │                  │
       │                   (Evaluates Warehouse)      │        │ (Rejected Path)  │ (Approved Path)
       │                                              ├────────┴──────────────────┼────────┐
       │                                              ▼                           │        │
       └─► [ postgres:18 ]                    [ restaurant-rejected-topic ] ──────┘        │
           ├─ order_service_schema                    │ (consume_restaurant_rejected_event)│
           ├─ payment_service_schema                  │                                    ▼
           ├─ restaurant_service_schema               ▼                       [ restaurant-approved-topic ]
           └─ user_service_schema              (payment-service Triggers Refunds)          │ (consume_restaurant_approved_event)
                                               (order-service Marks CANCELLED)             ▼
                                                                             (order-service Marks SUCCESS)
```
---

## ⚙️ Service-Specific Environment Profiles

This service adheres to the global ecosystem profiles (`default` / `k8sdb`) with the following localized database behaviors:

### 1. Default Profile (`default`)
* **Target Layer**: Local, in-memory volatile H2 database infrastructure.
* **Launch Flag**: Automatically active if no environment variable mappings are provided.

### 2. Kubernetes Pod Profile (`k8sdb`)
* **Target Layer**: Centralized, persistent `postgres:18-alpine` node inside the cluster framework.
* **Segregation Vector**: Dynamically locks read/write scopes to its dedicated service schema via:
  ```properties
  # NOTE: Replace 'order_service_schema' with 'payment_service_schema', etc. per service
  spring.datasource.url=jdbc:postgresql://postgres-db:5432/saga_db?currentSchema=order_service_schema
  ```
* **Activation Command**: Force using the java system property argument: `-Dspring.profiles.active=k8sdb`.

---

## ⚙️ Multi-Stage Saga State Machine Resolution

The service acts as an event-driven orchestrator across twin entry channels. It uses an internal `OrderConsumerConfig` class to intercept inbound messages and mount them onto an active OpenTelemetry `CONSUMER` span before calling the transaction layers.

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
* **🟢 Success Inbound Resolver (`restaurantApproved`)**: 
  * **Stream Source**: Pulls payloads from `restaurant-approved-topic`.
  * **Tracer Identifier**: Mounts the span `consume_restaurant_approved_event`.
  * **State Shift**: Calls `orderService.confirmOrder()` to change the identifier from `PENDING` to `SUCCESS`.
* **🔴 Compensation Inbound Resolver (`restaurantRejected`)**: 
  * **Stream Source**: Pulls payloads from `restaurant-rejected-topic`.
  * **Tracer Identifier**: Mounts the span `consume_restaurant_rejected_event`.
  * **State Shift**: Calls `orderService.cancelOrder()` to flip status codes to `CANCELLED`.

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

* **`PESSIMISTIC_WRITE`**: Places an exclusive row-level lock on pulled rows to prevent concurrent mutations.
* **Timeout Hint (`-2`)**: Instructs Hibernate to append `FOR UPDATE SKIP LOCKED` to the compiled SQL string. Nodes silently skip currently locked rows and process the next free entries.

### 2. Scheduled Outbox Publishing Loop
The `OutboxPublisherService` runs continuously on an automated background loop to clear pending entries:
* **Poller Target**: Queries the database repository every 500ms using a configured batch chunk limit.
* **Message Assembly**: Converts outbox JSON strings back into concrete Java payloads and sets up a standard Spring `Message` containing the original aggregate ID as the partition key.
* **Atomic Deletion**: Calls `streamBridge.send()` to push the record to `order-created-topic`. The database row is safely deleted only after a successful broker acknowledgment.

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
* **Background Task Telemetry Suppression**: The custom `ObservationPredicate` bean completely strips out observation metrics for the `publishNextOrderOutboxBatch` background poller loop executed by the `OutboxPublisherService` every 500ms.
* **Actuator Noise Suppression**: The tracking loop drops performance metrics for monitoring routes matching `/actuator/health` or `/actuator/info` by inspecting the servlet-based `.getCarrier().getRequestURI()` path extractor.

### 3. Custom Logback Event Suppression
The service registers a custom `MessageContainsFilter` inside its `logback-spring.xml` configuration to prevent transactional outbox database polling dumps from flooding stdout or filling the OTLP log streams to Grafana Loki:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <filter class="dev.tunmin.saga.orderservice.config.logging.MessageContainsFilter">
            <keywords>outbox_record</keywords>
        </filter>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-}, %X{spanId:-}] [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <filter class="dev.tunmin.saga.orderservice.config.logging.MessageContainsFilter">
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

* **Scheduled Task Exclusion**: The bean captures execution frames named `tasks.scheduled.execution` and completely blocks the `publishNextOrderOutboxBatch` scheduled task from generating observation metrics.
* **Actuator Path Filtering**: It scans inbound servlet operations via `ServerRequestObservationContext` and strips tracking data for endpoints matching `/actuator/health` or `/actuator/info` using the servlet-based `.getCarrier().getRequestURI()` path extractor.

