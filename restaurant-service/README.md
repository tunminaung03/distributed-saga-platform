# 🍳 Kitchen Ticket & Warehouse Stock Inventory Engine (`restaurant-service`)

[⬅️ Back to Master README](../README.md)

The `restaurant-service` governs the menu catalog, product availability checks, and kitchen ticket creation within the Distributed Saga ecosystem. Operating as an event-driven engine via **Spring Cloud Stream Kafka**, the service evaluates transaction messages asynchronously, handles stock deduction, and broadcasts results using a highly resilient, lock-free **Transactional Outbox Pattern**.

---

## 🚀 Core Functional Responsibilities
* **Event-Driven Inventory Valuator**: Processes `PaymentProcessedEvent` payloads over Kafka with native transactional boundaries (`read_committed`).
* **High-Concurrency Row Lock Bypass**: Leverages a pessimistic write lock combined with a `-2` persistence timeout hint to append `SKIP LOCKED` to queries, avoiding execution bottlenecks during high-frequency parallel polling.
* **Transactional Outbox Broker**: Separates application logic from network messaging by staging state transitions in an internal `OutboxRecord` data table before safely emitting them to Kafka topics.
* **Proactive Idempotency Protection**: Scans an internal ticket registry using an `existsByOrderId` check to instantly ignore duplicate event deliveries without reprocessing state.
* **Dual-Mechanism Log Suppression**: Integrates custom filtering logic across console layouts and OTLP pipelines to block recurring structural data blocks from inflating storage space.

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
               [ payment-processed-topic ]
                            │
              (W3C Propagation Context Header)
                            ▼
           [ restaurantDecision Inbound Bean ]
          (OpenTelemetry CONSUMER Span Context)
                            │
               ☕ Virtual Thread Execution
                            │
              🛡️ Proactive Idempotency Check
                            │
            📊 verifyAndDeductStock Processing
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
      [ ALLOCATED ]  [ INSUFFICIENT ]  [ ITEM_NOT_FOUND ]
            │               │               │
      Create Ticket   Create Ticket   Create Ticket
       (PREPARING)     (REJECTED)      (REJECTED)
            │               │               │
            └───────────────┼───────────────┘
                            ▼
    💾 OutboxRecord Table + Captures Active Trace/Span ID
                            │
       PESSIMISTIC_WRITE Lock with SKIP LOCKED (-2 Timeout)
                            ▼
    ⚙️ OutboxPublisherService Scheduled Task Loop (500ms)
       (Reconstructs Micrometer TraceContext Frame)
                            │
            ┌───────────────┴───────────────┐
            ▼                               ▼
[ restaurant-approved-topic ]   [ restaurant-rejected-topic ]
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
  spring.datasource.url=jdbc:postgresql://postgres-db:5432/saga_db?currentSchema=restaurant_service_schema
  ```
* **Activation Command**: Force using the java system property argument: `-Dspring.profiles.active=k8sdb`.

---

## ⚙️ Advanced Persistence & Concurrency Control

The engine prevents system lockups when multiple application instances query the outbox simultaneously by configuring high-performance query boundaries:

```java
public interface OutboxRepository extends JpaRepository<OutboxRecord, UUID> {
    
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    List<OutboxRecord> findByOrderByCreatedTimeAsc(Pageable pageable);
}
```
* **PESSIMISTIC_WRITE**: Places an exclusive row-level lock on matching data rows to prevent concurrent balance mutations.
* **Timeout Hint (`-2`)**: Instructs Hibernate to append `FOR UPDATE SKIP LOCKED` directly to the compiled SQL string. Instead of blocking or failing when it encounters a locked row, the worker silently skips it and moves to the next free entry.

---

## 💾 Predefined Idempotent Catalog Dataset (`data.sql`)

The database initialized routine loads structural stock allocations on initialization to support edge-case verification steps:

```sql
SET search_path TO restaurant_service_schema;

-- Idempotent Stock Master Allocations
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('BURGER_01', 50) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('PIZZA_01', 30) ON CONFLICT (item_code) DO NOTHING;

-- Target Low-Stock Edge Case Entries
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('SUSHI_02', 3) ON CONFLICT (item_code) DO NOTHING;
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('STEAK_02', 2) ON CONFLICT (item_code) DO NOTHING; -- Triggers Low Stock Deficit test path

-- Compensating Workflow Targets (Sold Out)
INSERT INTO restaurant_inventory (item_code, stock_count) VALUES ('TACO_02', 0) ON CONFLICT (item_code) DO NOTHING;  -- Triggers Saga Reverse Rollback path
```

---

## 🧼 High-Density Telemetry Noise Isolation

To remain compliant with the strict **5.5GB cluster limit** and prevent log-aggregation engines from overloading, the service drops structural heartbeats and routine outbox polling frames before they hit network interfaces.

### 1. Dual-Protocol Telemetry Matrix
The service pushes metrics, traces, and log data out of the container using segmented transmission channels:

```yaml
management:
  otlp:
    metrics:
      export:
        url: "http://\${OTEL_COLLECTOR_HOST:otel-collector.monitoring}:4318/v1/metrics"  # HTTP REST channel
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
* **Micrometer Offset Lag Metric Drop**: The engine completely excludes heavy partition offset tracking data from Prometheus scraping collectors via an explicit deny filter to preserve memory limits:

```java
  @Bean
  MeterFilter disableKafkaLagMetrics() {
      return MeterFilter.denyNameStartsWith(KafkaBinderMetrics.OFFSET_LAG_METRIC_NAME);
  }
  ```
  
* **Active Tracing Context Reconstruct Loop**: When emitting events, the `OutboxPublisherService` background worker extracts the raw `traceId` and `spanId` strings from the database `OutboxRecord`. It passes them to Micrometer's context builder to reconstruct the W3C trace parent headers (`00-[trace_id]-[span_id]-01`) dynamically, ensuring continuous distributed span visibility across Kafka bounds.
* **Background Task Telemetry Suppression**: The custom `ObservationPredicate` bean completely strips out observation metrics for the `publishNextRestaurantOutboxBatch` background poller loop executed by the `OutboxPublisherService` every 500ms to avoid flooding your telemetry dashboard.
* **Actuator Path Filtering**: The tracking loop drops performance metrics for monitoring routes matching `/actuator/health` or `/actuator/info` by inspecting the servlet-based `.getCarrier().getRequestURI()` path extractor.


### 3. Custom Logback Event Suppression
The application registers a custom `MessageContainsFilter` to prevent local console clutter and isolate storage footprints from outbox table polling details:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <filter class="dev.tunmin.saga.restaurantservice.config.logging.MessageContainsFilter">
            <keywords>outbox_record</keywords> <!-- Drops database raw table dump strings -->
        </filter>
        <encoder>
            <pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} [%X{traceId:-}, %X{spanId:-}] [%thread] %-5level %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <appender name="OTEL" class="io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender">
        <filter class="dev.tunmin.saga.restaurantservice.config.logging.MessageContainsFilter">
            <keywords>outbox_record</keywords> <!-- Prevents Grafana Loki flooding -->
        </filter>
    </appender>
</configuration>
```

### 4. Micrometer Offset Lag Metric Drop
The engine excludes heavy partition offset tracing data from Prometheus scraping collectors via an explicit deny filter:

```java
@Bean
MeterFilter disableKafkaLagMetrics() {
    return MeterFilter.denyNameStartsWith(KafkaBinderMetrics.OFFSET_LAG_METRIC_NAME);
}
```

### 5. Active Tracing Context Reconstruct Loop
When emitting events, the `OutboxPublisherService` extracts the raw `traceId` and `spanId` strings from the database table record. It passes them to Micrometer's context builder to reconstruct the W3C trace parent headers (`00-[trace_id]-[span_id]-01`) dynamically, ensuring continuous distributed span visibility across Kafka boundaries.

### 6. Background Task Telemetry Suppression
Because the transactional outbox pipeline runs continuously every 500ms, its scheduled execution metrics would heavily flood your telemetry storage. The service deploys a custom `ObservationPredicate` bean to strip out this polling noise:

* **Scheduled Task Exclusion**: The bean captures execution frames named `tasks.scheduled.execution` and cross-checks the method details against the `app.observability.ignored.tasks` parameter.
* **Isolating the Polling Core**: It completely blocks the `publishNextRestaurantOutboxBatch` scheduled task from generating observation metrics.
* **Actuator Path Filtering**: It scans inbound servlet operations via `ServerRequestObservationContext` and strips tracking data for endpoints matching `/actuator/health` or `/actuator/info` using `.getCarrier().getRequestURI()`.
