# 🗺️ Edge Perimeter API Gateway Engine (`api-gateway-service`)

[⬅️ Back to Master README](../README.md)

The `api-gateway-service` acts as the definitive security perimeter and non-blocking routing backbone for the Distributed Saga ecosystem. Powered by **Spring Cloud Gateway**, **Spring WebFlux (Netty)**, and **Java 25**, this component handles inbound client ingress, executes token-based perimeter guards, provides circuit breaking protection, and propagates tracing context downstream.

---

## 🚀 Core Functional Responsibilities
* **Hybrid Transport Routing**: Directs high-throughput public REST traffic downstream to `order-service` while resolving user logins via high-performance internal gRPC loops.
* **Perimeter Security Guard**: Evaluates incoming `Authorization: Bearer [JWT]` tokens, blocks malformed tokens via specialized exceptions, and drops unsafe headers at the border.
* **Resilience Circuit Catcher**: Intercepts unmapped downstream HTTP failures using a count-based **Resilience4j Circuit Breaker** filter that issues standardized `SERVICE_UNAVAILABLE` JSON models.
* **Non-Blocking Virtual Executions**: Leverages native Java 25 Virtual Threads inside a non-blocking `Mono.fromCallable()` wrapper, freeing WebFlux Netty threads during blocking gRPC handshakes.
* **Asynchronous Telemetry Enrichment**: Features a unified exception interceptor that separates structural 4xx user validation noise from critical 5xx infrastructure crashes, appending metadata to live OpenTelemetry Spans.

---

## 🛠️ Technology Stack & Runtime Profile
* **Runtime Environment**: Java 25 Engine (Eclipse Temurin Alpine JRE)
* **Core Framework**: Spring Boot 4.0.6 & Spring Cloud 2025.1.1
* **Reactive Engine**: Spring WebFlux / Project Reactor Netty
* **Inter-Service Communication**: `spring-grpc-client` (v1.0.3) utilizing proto channels
* **Resilience Framework**: Resilience4j Reactor Starter
* **Security & Crypto Parsing**: Java-JWT (`com.auth0:java-jwt:4.5.2`)

---

## 📜 gRPC Protocol Buffer Contract Specification

The edge proxy consumes pre-compiled serialization stubs to handle internal token signatures with the identity store over high-speed plaintext streams:

```protobuf
syntax = "proto3";

option java_multiple_files = true;
option java_package = "dev.tunmin.saga.user.grpc";

service UserService {
  rpc AuthenticateUser (AuthRequest) returns (AuthResponse);
}

message AuthRequest {
  string email = 1;
  string password = 2;
}

message AuthResponse {
  string token = 1;
  string token_type = 2;
  int64 expires_in = 3;
}
```
* **Runtime Mechanics**: The `AuthenticationController` auto-wires the compiled `UserServiceBlockingStub` bean to transform public HTTP request parameters into lightweight proto binary blocks mapped to `static://user-service:9090`.

---

## 🎛️ Architecture & Perimeter Routing Matrix

```text
                 [ Public Inbound Client Request ]
                             │
                     (NodePort: 30080)
                             ▼
               [ api-gateway-service:8080 ]
                    (WebFlux / Netty)
                             │
                    💡 Path Predicate Filter
                             │
       ┌─────────────────────┴─────────────────────┐
       ▼                                           ▼
 [ /auth/login (POST) ]                  [ /orders/** ]
       │                                           │
 ☕ Virtual Thread Loop                     🔒 JwtPerimeterGuard Filter
       │                                           │
 📡 gRPC Stub Client Channel                       ▼
 (user-service:9090 via Plaintext)       💥 Resilience4j Circuit Breaker
       │                                           │ (Success Path)
       ▼                                           ▼
[ user-service ]                         [ order-service:8081 ]
```

---

## ⚙️ Service-Specific Environment Profiles

The gateway uses operational profiles to seamlessly transition routing targets between localized host environments and Kubernetes cluster networks.

### 1. Default Profile (`default`)
* **Routing Vector**: Forwards incoming traffic to microservices running locally on your workstation (`localhost`).

### 2. Kubernetes Pod Profile (`k8sdb`)
* **Routing Vector**: Leverages Kubernetes CoreDNS and Cilium Service Mesh/CNI naming conventions to route edge traffic directly to internal cluster pods via native service resolution:

  ```yaml
  uri: 'http://\({ORDER_SERVICE_HOST:order-service}:\){ORDER_SERVICE_PORT:8081}'
  ```

* **Activation Command**: Activated via the container environment variable setup inside the deployment manifests.

---

### Route Entry Registry

1. **Edge Authentication Route (`auth-token-route`)**
   * **Predicate**: `Path=/auth/login`, `Method=POST`
   * **Action**: Proxies calls locally to `AuthenticationController`. The container uses a `UserGrpcClient` channel (`static://user-service:9090`) to request signed tokens from the identity store.

2. **Guarded Order Engine Route (`order-service-route`)**
   * **Predicate**: `Path=/orders/**`
   * **Action**: Routes directly to `http://order-service:8081` through these active filters:
     * `JwtPerimeterGuard`: Verifies tokens before letting the request pass deeper into the cluster.
     * `RemoveRequestHeader=Cookie`: Strips raw client cookies at the entry layer.
     * `CircuitBreaker`: Intercepts socket dropouts and triggers `GatewayFallbackController` via `forward:/fallback/orders`.

---

## ⚙️ Production Configuration Blueprint

The service configuration uses specialized parameters to control performance boundaries and maintain high availability:

### 1. Java 25 Virtual Thread Activation
Engine threads are offloaded onto JVM-managed virtual maps to ensure non-blocking routing performance:
```yaml
spring:
  threads:
    virtual:
      enabled: true
```

### 2. Reactive Context Propagation
Maintains trace and span IDs across reactive threads, ensuring data flows cleanly into OpenTelemetry and Cilium eBPF layers:
```yaml
spring:
  reactor:
    context-propagation: auto
```

### 3. Resilience4j Circuit Breaker Sizing
The `/orders/**` endpoint is protected by a count-based safety loop designed for rapid recovery:
```yaml
resilience4j:
  circuitbreaker:
    instances:
      orderServiceCircuitBreaker:
        slidingWindowType: COUNT_BASED
        slidingWindowSize: 10              # Inspects the last 10 requests
        failureRateThreshold: 50           # Trips circuit if ≥ 50% fail
        waitDurationInOpenState: 15000ms   # Holds open for 15 seconds before trying half-open
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
```

### 4. Dynamic Ingress Port Mapping
The container exposes network interfaces via `containerPort: 8080` bound to the structural identifier `name: http-ingress`. All infrastructure probes and cluster services route traffic to this name, letting the `GATEWAY_PORT` variable handle port changes dynamically.

---

## 🔒 Perimeter Boundary Guard & Exception Strategy

The service features a comprehensive `@RestControllerAdvice` engine (`GlobalExceptionHandler`) to ensure malformed traffic or service issues produce standardized JSON error definitions:

### Standardized Edge Error Envelopes
When business logic errors or platform issues cross the gateway, the engine produces a consistent, predictable error model:
```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "The order placement infrastructure is temporarily overloaded. Please try again in a few moments.",
  "timestamp": 1784013301000
}
```

### Error Mitigation Matrix

| Exception Class | Translated HTTP Status | System Boundary Purpose |
| :--- | :--- | :--- |
| `WebExchangeBindException` | `400 Bad Request` | Catches invalid request bodies at the entry gateway before they can consume cluster compute. |
| `InvalidCredentialsException` | `401 Unauthorized` | Rejects invalid username/password inputs during gRPC logins. |
| `JWTVerificationException` | `401 Unauthorized` | Intercepts malformed, faked, or broken incoming security tokens. |
| `UserInactiveException` | `403 Forbidden` | Blocks deactivated accounts from initiating system transactions. |
| `TokenExpiredException` | `403 Forbidden` | Rejects expired tokens and requests a session refresh. |
| `UserNotFoundException` | `404 Not Found` | Returned when requested accounts are missing from the identity pool. |
| `DependencyUnavailableException` | `503 Service Unavailable` | Catches connection issues with downstream elements. |
| `Exception` | `500 Internal Error` | Fallback handler that safely shields internal code stacks from clients. |

### Pure Telemetry Extractor Method
To avoid cluttering your distributed traces with common user errors, the gateway filters errors before recording them to the monitoring backend:

* **📉 4xx Client Validation Validation**: Spans are tagged with standard HTTP statuses and exception names, but they are not flagged as system defects. This keeps your dashboards clean.
* **💥 5xx Infrastructure Crashes**: When real backend system failures occur, the engine marks the underlying `Span` status as `StatusCode.ERROR` and records the complete stack trace directly to **Grafana Tempo**.

---

## 📊 Dual-Protocol Telemetry Matrix

The gateway pushes metrics, traces, and log data out of the application container using segmented transmission channels:

```yaml
management:
  otlp:
    metrics:
      export:
        url: "http://\${OTEL_COLLECTOR_HOST:otel-collector.monitoring}:4318/v1/metrics"  # HTTP REST path
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

### 🧼 Reactive Telemetry Noise Isolation
The gateway implements a custom `ObservationPredicate` bean (`ObservationConfig`) to filter out routine platform metrics and focus on business operations. Because this service runs on a reactive **WebFlux Netty loop**, it features a key technical difference from the rest of the ecosystem's servlet-based services:

* **Reactive Context Matching**: The gateway leverages the non-blocking context identifier `org.springframework.http.server.reactive.observation.ServerRequestObservationContext`.
* **Path Extraction Strategy**: Sockets must resolve paths reactively via `.getCarrier().getURI().getPath()` instead of the standard servlet-blocking `.getCarrier().getRequestURI()` mechanism to extract metrics properly.
* **Perimeter Path Suppression**: The loop drops performance tracking for routes matching configuration elements inside the `app.observability.ignored.paths` string payload (such as `/actuator/health` and `/actuator/info`).
* **Scheduled Task Metrics Exclusion**: Scans execution metadata matching `tasks.scheduled.execution` frames and drops all observation records whose method names are declared inside the `app.observability.ignored.tasks` parameter registry.

---

## 🧪 Local Verification & Health Audit

### 1. Check Container Health Status
Query the application actuator endpoints to audit readiness state machine variables:
```bash
curl -X GET http://localhost:8080/actuator/health
```

### 2. Verify Circuit Breaker Fallback Path
Simulate a down-stream dependency outage by shutting down the `order-service` pod and sending a transaction payload. Verify the gateway intercepts the call and handles the fallback scenario gracefully:
```bash
curl -X POST http://localhost:8080/orders \
  -H "Authorization: Bearer [JWT_TOKEN]" \
  -H "Content-Type: application/json" \
  -d "{\"customerId\": \"1\", \"totalAmount\": 15.99, \"itemCode\": \"PIZZA_01\", \"quantity\": 2}"
```

#### Expected Resilience Response Payload
```json
{
  "error": "SERVICE_UNAVAILABLE",
  "message": "The order placement infrastructure is temporarily overloaded. Please try again in a few moments.",
  "timestamp": 1784013301500
}
```
