# 📐 Distributed Saga Component Architecture Spec

This specification details the structural design patterns, data boundaries, and asynchronous event-driven state engines that govern the `sample-microservice-saga` platform ecosystem.

---

## 🔀 1. Distributed Saga Orchestration Engine

The ecosystem handles business operations through an asynchronous Orchestrator-driven Saga Pattern. Instead of services calling each other directly, state transitions are centralized and managed by the `order-service` state engine.

### Transaction Lifecycle Vector Map
```text
  [ Order Created ] ──► (Mark PENDING) 
         │
         ▼
  [ Publish: order-created-topic ] ──► (payment-service consumes)
         │
         ├───► 🟢 Success Vector: Balance Captured ➔ Publish: payment-processed-topic
         │                                               │
         │                                               ▼
         │                                   (restaurant-service consumes)
         │                                               │
         │                                               ├──► 🟢 Confirm Stock ➔ Mark ORDER_SUCCESS
         │                                               │
         │                                               └──► 🔴 Stock Deficit ➔ Publish: restaurant-rejected-topic
         │                                                                             │
         └───► 🔴 Failure Vector: Ledger Overdraft ────────────────────────────────────┤
                                                                                       ▼
                                                                        (payment-service Triggers Refunds)
                                                                        (order-service Marks CANCELLED)
```

---

## 🖧 2. Bare-Metal Windows KRaft Kafka Protocol Blueprint

To support both external bare-metal host development (IDE) and encapsulated virtual cluster execution concurrently without cluster consensus metadata collisions, the Kafka engine handles traffic routing through twin, isolated protocol zones.

```text
                                [ Windows Laptop Host ]
                    ┌──────────────────────┴──────────────────────┐
                    ▼                                             ▼
       [ Port 9092: INTERNAL ]                       [ Port 9094: EXTERNAL ]
       - Bound to: 127.0.0.1                         - Bound to: 0.0.0.0
       - Targets: Local IDE Processes                - Targets: Minikube Pod Traffic
       - Isolated Loopback Memory                    - Traverses Hyper-V Nat Switches
```

### Immutable Host Broker Network Mappings
The host-side broker process running via `config/server.properties` implements this exact listener configuration:
```properties
listener.security.protocol.map=INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT,CONTROLLER:PLAINTEXT
listeners=INTERNAL://127.0.0.1:9092,EXTERNAL://0.0.0.0:9094,CONTROLLER://127.0.0.1:9093
advertised.listeners=INTERNAL://localhost:9092,EXTERNAL://host.minikube.internal:9094
inter.broker.listener.name=INTERNAL
controller.listener.names=CONTROLLER
```

---

## 💾 3. Logical Database Schema Segregation

To maintain strict compliance with the independent microservice database-per-service pattern under a highly constrained 5.5GB RAM node footprint, multiple heavyweight database engines are rejected. A singular, centralized `postgres:18-alpine` instance segregates transactional tables into four logical boundaries via an `init.sql` script.

### Storage Isolation Map
```text
  [ postgres-db Instance (saga_db) ]
        ├── order_service_schema       ➔ customer_orders, outbox_record
        ├── payment_service_schema     ➔ payments, outbox_record
        ├── restaurant_service_schema  ➔ restaurant_inventory, restaurant_tickets, outbox_record
        └── user_service_schema        ➔ users
```

### Symmetric Microservice Runtime Profile Datasource Strings
Microservices query connection endpoints driven dynamically from global config maps using this exact layout format to isolate data mutated inside their respective schema boundaries:
```properties
spring.datasource.url=jdbc:postgresql://\({DATABASE_HOST:postgres-db}:\){DATABASE_PORT:5432}/saga_db?currentSchema=[target_service_schema]
```

---

## 🔁 4. Asynchronous Outbox Messaging Mechanics

To prevent distributed data synchronization failures and eliminate unstable, dual-write conditions (updating the database while simultaneously pushing a message to a broker), all services implement a strict **Transactional Outbox Pattern**.

### Outbox Processing Execution Cycle
1. **Atomic Local Commit**: The microservice business transaction updates its domain tables (e.g., `customer_orders`) and writes a corresponding event record into its local schema table `outbox_record` inside a singular, atomic database transaction lock.
2. **Background Poller Sweeps**: A continuous, non-blocking background scheduled service (`OutboxPublisherService`) polls the local `outbox_record` table every **500 milliseconds** for unexported rows (`status = 'PENDING'`).
3. **Broker Dispatch & Acknowledgment**: The poller dispatches the payload to the respective Kafka stream topic over port `9094`. Upon receiving a secure partition receipt acknowledgement from the broker, the poller updates the database record state to `PROCESSED` or deletes the row to prevent event re-delivery.

---

## 🏷️ 5. Metadata Labeling & Cluster Resource Organization Strategy

The platform enforces a standardized, queryable metadata structure using official Kubernetes App-Delivery semantics (`app.kubernetes.io/*`). Rather than relying on arbitrary loose naming tokens, this framework acts as a foundational mapping plane for security sandboxing, metric aggregation, and automated telemetry tagging.

### Production-Grade Metadata Map Matrix

The entire cluster topology maps cleanly across namespaces using three logical selectors:

| Workload Domain (`part-of`) | Target Service Component (`name`) | Functional Role Key (`component`) | Namespace Boundary |
| :--- | :--- | :--- | :--- |
| **`saga-platform`** | `api-gateway-service` | `gateway` | `saga-ecosystem` |
| **`saga-platform`** | `order-service` | `backend` | `saga-ecosystem` |
| **`saga-platform`** | `payment-service` | `backend` | `saga-ecosystem` |
| **`saga-platform`** | `restaurant-service` | `backend` | `saga-ecosystem` |
| **`saga-platform`** | `user-service` | `backend` | `saga-ecosystem` |
| **`saga-platform`** | `postgres-db` | `database` | `saga-ecosystem` |
| **`monitoring`** | `otel-collector` | `collector` | `monitoring` |
| **`monitoring`** | `prometheus` | `metrics` | `monitoring` |
| **`monitoring`** | `loki` | `logging` | `monitoring` |
| **`monitoring`** | `tempo` | `tracing` | `monitoring` |
| **`monitoring`** | `grafana` | `dashboard` | `monitoring` |

---

### 🔒 Operational Engineering Impacts & Least-Privilege Access Control

This uniform labeling matrix provides critical, multi-dimensional advantages across your sidecarless virtualization layers:

#### 1. Fine-Grained Layer 7 eBPF Access Control Rules
Cilium operates on a default-deny posture. Because workloads are strictly classified by their `part-of` and `component` labels, you can write highly granular network policies. 
* *Example:* The `common-health-probes.yaml` policy whitelists host-level Kubelet traffic strictly for pods matching `part-of: saga-platform` and `component: backend` or `gateway`, completely insulating your stateful database and infrastructure nodes from probe overhead.

#### 2. Cross-Namespace Perimeter Egress Security
Instead of whitelisting wide network CIDR blocks or hardcoding shifting internal pod IP structures, security definitions rely on clean selector expressions. 
* *Example:* The `observability-egress-policy.yaml` dynamically constructs safe outbound paths by explicitly matching pods carrying `name: otel-collector` residing inside `namespace: monitoring`, blocking unvetted lateral data leakage across namespace walls.

#### 3. Low-Footprint Prometheus Scraper Optimization
Prometheus skips expensive, heavy Kubernetes API multi-namespace discovery routines. It uses standard label matching queries to scrape targets synchronously across namespace splits, maintaining an incredibly low memory footprint well under your cluster limits.

#### 4. Automated OpenTelemetry Attrib Ingestion
The unified `otel-collector` leverages these exact metadata variables inside its processor blocks to automatically stamp logs, metrics, and trace spans with resource contexts upon network card arrival, enabling seamless split-screen triage routines inside Grafana dashboards.
