# 🌐 Distributed Saga Ecosystem (Java 25 & Sidecarless Cilium eBPF)

This repository contains a cloud-native microservice platform demonstrating the Distributed Saga Orchestration pattern for long-running business transactions. Built with Java 25, Spring Boot 4, Spring Cloud Gateway, Apache Kafka, Kubernetes, and Cilium eBPF, it showcases secure service-to-service communication, event-driven workflows, distributed observability, and least-privilege network security in a local Kubernetes environment.

## Highlights

- Java 25 + Spring Boot 4 microservices
- Event-driven Saga Choreography with Transactional Outbox
- Apache Kafka event-driven communication
- Spring Cloud Gateway + JWT authentication
- gRPC inter-service communication
- Kubernetes (Minikube)
- Cilium eBPF L7 Network Policies
- OpenTelemetry + Tempo + Loki + Prometheus + Grafana
- Java Virtual Threads

---

## 🏗️ Architectural Topology

```text
                    Client
                       │
                       ▼
                 API Gateway
                 │         │
             gRPC│         │HTTP
                 ▼         ▼
          User Service  Order Service
                            │
                            ▼
              Kafka (OrderCreatedEvent)
                            │
                            ▼
                    Payment Service
                            │
                            ▼
           Kafka (PaymentProcessedEvent)
                            │
                            ▼
                  Restaurant Service
                     │            │
                     │            │
Kafka (RestaurantApprovedEvent)  Kafka (RestaurantRejectedEvent)
               │                │
               ▼                ├──────────────► Payment Service
        Order Service           │               (Compensation)
                                ▼
                          Order Service


        Cilium eBPF L7 Policies protect service-to-service traffic


                  PostgreSQL
          ├── order schema
          ├── payment schema
          ├── restaurant schema
          └── user schema
```

---

## 🗂️ Repository Directory Blueprint

* **📂 [`/`](./README.md)**: Main infrastructure orchestration root workspace.
* **📂 [`manifests/apps/`](./manifests/apps/)**: Microservice application deployment templates.
* **📂 [`manifests/monitoring/`](./manifests/monitoring/)**: Telemetry engines and independent configuration mappings.
* **📂 [`manifests/network-policies/`](./manifests/network-policies/)**: Granular, least-privilege `CiliumNetworkPolicy` manifests.
* **📂 [`docs/`](./docs/)**: Decentralized platform manuals, runbooks, and deep-dive specifications.
* **📂 [`api-gateway-service/`](./api-gateway-service/README.md)**: Inbound Edge Proxy built on Spring Cloud Gateway and reactive Netty loop.
* **📂 [`order-service/`](./order-service/README.md)**: State Machine and core Saga Orchestrator engine.
* **📂 [`payment-service/`](./payment-service/README.md)**: Financial transaction ledger settlement engine.
* **📂 [`restaurant-service/`](./restaurant-service/README.md)**: Kitchen ticket execution and inventory verification processor.
* **📂 [`user-service/`](./user-service/README.md)**: Identity profiles, security credentials, and access governor.
* **📂 [`user-contract/`](./user-contract/)**: Shared serialization models and shared API contract boundaries.

---

## 💻 Workstation Resource Constraints & Prerequisites

To execute this architecture on a local workstation, ensure your host environment meets these baseline technical specifications:

* **Host OS**: Windows 11 Pro, macOS 14+, or Enterprise Linux distributions.
* **Hypervisor Engine**: Platform-specific native hypervisor (For Windows, Microsoft Hyper-V is used; Docker Desktop must be stopped to avoid resource lockups).
* **Minikube Version**: v1.37+
* **Compute Allocation**: Exactly 5.5 GB (5632MB) RAM and 3 Dedicated CPU Cores allocated to the VM instance.

---

## 📝 Comprehensive Reference Documentation Manuals

For advanced runbooks, structural designs, and triage blueprints, consult the specific decoupled guides:

1. 🚀 **[Local Minikube Cluster Provisioning & Setup Guide](./docs/local-minikube-setup.md)**
2. 📐 **[Distributed Saga Component Architecture Spec](./docs/architecture.md)**
3. 🔒 **[Least-Privilege Cilium Network Policy Manual](./docs/cilium-network-policy.md)**
4. 📊 **[Observability Ops & Grafana Triage Manual](./docs/observability.md)**
5. 🧯 **[Windows Hypervisor Environment Troubleshooting Guide](./docs/troubleshooting.md)**

---

## 🚀 Cross-Platform Cluster Provisioning Runbook

Execute these steps sequentially in an administrative shell (PowerShell/CMD on Windows, Terminal on macOS/Linux).

### Step 1: Initialize Target Runtime Plane
Select the specific virtualization engine corresponding to your workstation Operating System:

> 💡 **RESOURCE ALLOCATION NOTE**: While this ecosystem is heavily optimized and thoroughly verified to boot and run stable under a tight testing constraint of **5.5 GB RAM and 3 CPU cores**, allocating more resources (e.g., **6 GB, 7 GB, or 8 GB RAM** and **4 or more CPU cores**) is highly recommended if your host workstation has available headroom. This will drastically speed up compilation layers, shorten Spring Boot initialization cycles, and prevent container startup throttling.

```bash
# Windows 11 Pro Workstation (Hyper-V native)
minikube start --driver=hyperv --memory=5632 --cpus=3 --cni=cilium

# macOS Workstation (Apple Silicon / Intel via QEMU)
minikube start --driver=qemu --memory=5632 --cpus=3 --cni=cilium

# Linux Workstation (Native KVM/Docker driver execution)
minikube start --driver=docker --memory=5632 --cpus=3 --cni=cilium
```

### Step 2: Establish In-Cluster Container Storage Context
Point your current command terminal context directly to Minikube's internal Docker storage system:

```bash
# Windows Command Prompt (CMD)
@FOR /f "tokens=*" %i IN ('minikube -p minikube docker-env') DO @%i

# macOS / Linux / Windows WSL2 (Bash/Zsh)
eval \$(minikube -p minikube docker-env)
```

### Step 3: Compile Microservices Locally
Build assets straight into the active cluster container plane to avoid remote image pull failures. 

> ⚠️ **CRITICAL RUNTIME REQUIREMENT**: This step relies on an in-cluster context build strategy. Each microservice directory (`./api-gateway-service`, `./order-service`, etc.) must contain its own valid, multi-stage **`Dockerfile`** at its root to correctly parse the compilation layers and inject Java 25 production optimizations.

```bash
# Cross-Platform Maven compilation
mvn clean package -DskipTests

# Build local images inside Minikube storage context (Executes context-bound Dockerfiles)
docker build -t dev.tunmin.saga/api-gateway-service:1.0.0-rc1 ./api-gateway-service
docker build -t dev.tunmin.saga/order-service:1.0.0-rc1 ./order-service
docker build -t dev.tunmin.saga/payment-service:1.0.0-rc1 ./payment-service
docker build -t dev.tunmin.saga/restaurant-service:1.0.0-rc1 ./restaurant-service
docker build -t dev.tunmin.saga/user-service:1.0.0-rc1 ./user-service
```

### Step 4: Bootstrapping Infrastructure & Namespaces
Apply cluster topologies in order. Navigate into the root-level manifest directory:

```bash
cd manifests
```

1. Initialize active namespaces:
```bash
kubectl apply -f namespace-setup.yaml
```
   
2. Inject cluster specifications, credentials, and parameters:
```bash
kubectl apply -f cluster-configmap.yaml
kubectl apply -f ecosystem-secrets.yaml
```
   
3. Deploy the segregated persistence layer:
```bash
kubectl apply -f postgres-manifestst.yaml
```

### Step 5: Activate Core Monitoring Configurations & Dashboards
Install Gateway APIs and compile the core telemetry layers:

```bash
minikube addons enable gateway-api

# Deploy configurations and backing engines sequentially
kubectl apply -f monitoring/config/
kubectl apply -f monitoring/
```

### Step 6: Deploy Business Microservices
```bash
kubectl apply -f apps/
```

### Step 7: Apply Least-Privilege Network Policies

> ⚠️ **IDE DEPLOYMENT WARNING**: Applying these network policies enforces a strict, default-deny security posture across the pod canvas. **SKIP THIS STEP** if you are executing or debugging your applications locally inside your host IDE. Applying these manifests will block unvetted bare-metal Windows processes from crossing port-forward tunnels, causing instant database connection drops and telemetry stream rejections. Only apply these policies during full in-cluster container testing loops.

```bash
kubectl apply -f network-policies/
```

---

## 🧪 Ecosystem Verification & Transaction Testing

The ecosystem includes seed scripts (`data.sql`) that populate the shared database with predefined user profiles (`user-service`) and warehouse inventory items (`restaurant-service`). Follow this testing playbook to generate authorization tokens and validate Saga state machine workflows.

### 🔌 Phase 1: External Ingress Gateway Smoke Test
Verify that the `api-gateway-service` can accept external client requests and cleanly forward headers into downstream components:

```bash
# Via NodePort (Direct Cluster Node Address)
curl -X GET http://\$(minikube ip):30080/actuator/health

# Via Local Port-Forward Bridge Tunnel
curl -X GET http://localhost:8080/actuator/health
```

### 🔑 Phase 2: Authentication & JWT Token Generation
Generate an authorization bearer token by targeting the authentication endpoint through the edge proxy loop:

```bash
# 🟢 1. Premium Profile (User ID: 1)
curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\": \"developer@tunmin.dev\",\"password\": \"securePassword123\"}"

# 🟡 2. Standard Profile (User ID: 2)
curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\": \"user@tunmin.dev\",\"password\": \"securePassword123\"}"

# 🔴 3. Inactive Profile (User ID: 3)
curl -X POST http://localhost:8080/auth/login -H "Content-Type: application/json" -d "{\"username\": \"suspended@tunmin.dev\",\"password\": \"securePassword123\"}"
```
*Copy the returned string from the response to replace the `[JWT_TOKEN]` value in the transaction steps below.*

### 🔀 Phase 3: Distributed Saga Workflow Testing

#### Scenario A: The Happy Flow Path
Executes a successful end-to-end transaction. Order created, payment captured, and restaurant ticket confirmed.
```bash
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer [JWT_TOKEN]" \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"1\", \"totalAmount\": 15.99, \"itemCode\": \"PIZZA_01\", \"quantity\": 2}"
```

#### Scenario B: User ID Context Mismatch
Triggers a security boundaries failure. The payload body contains a customer identifier that does not match the embedded token claims.
```bash
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer [JWT_TOKEN]" \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"2\", \"totalAmount\": 15.99, \"itemCode\": \"PIZZA_01\", \"quantity\": 2}"
```

#### Scenario C: Warehouse Low Stock Deficit
The database inventory tracker contains insufficient stock quantities for the requested catalog item code (`STEAK_02`), triggering an instant business rejection.
```bash
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer [JWT_TOKEN]" \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"2\", \"totalAmount\": 45.00, \"itemCode\": \"STEAK_02\", \"quantity\": 2}"
```

#### Scenario D: Full Asynchronous Saga Compensation Workflow
Simulates a mid-flight ecosystem failure. The order is provisionally reserved, but downstream business rule violations force the system to trigger compensating reverse-transactions across the network card space to roll back the state safely.
```bash
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer [JWT_TOKEN]" \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"1\", \"totalAmount\": 8.50, \"itemCode\": \"TACO_02\", \"quantity\": 1}"
```

#### Scenario E: Hibernate Validation Framework Boundary Interception
Validates edge constraints before processing traffic. The framework blocks missing customer fields, negative pricing strings, or invalid zeros in product counters.
```bash
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer [JWT_TOKEN]" \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"\", \"totalAmount\": -5.00, \"itemCode\": \"BURGER_01\", \"quantity\": 0}"
```

### 📊 Phase 4: Auditing Asynchronous Cluster Telemetry
Inspect the active execution metrics of the system directly through the eBPF runtime network layer:
```bash
# View real-time L7 HTTP flow logs intercepted via Cilium kernel hooks
cilium hubble observe --namespace saga-ecosystem --protocol http --follow
```
