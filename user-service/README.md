# 🔑 Identity Profile & Access Governor Engine (`user-service`)

[⬅️ Back to Master README](../README.md)

The `user-service` is the authoritative credential registry, identity profile layer, and session token provisioning engine for the Distributed Saga ecosystem. Operating via a high-performance **Spring gRPC Server** over port `9090`, the service securely verifies application access using virtualized non-blocking threads, cross-checks password hashes via `jbcrypt`, and signs contextual JWT authorizations.

---

## 🚀 Core Functional Responsibilities
* **gRPC Identity Provider**: Serves authentication requests using high-efficiency proto channels over port `9090` without HTTP serialization overhead.
* **Idempotent Data Seeding**: Evaluates an internal `data.sql` script on startup using idempotent `ON CONFLICT` constraints to ensure safe local workstation testing states.
* **Cryptographic Verification**: Validates inbound secrets using salt-aware **BCrypt** layout algorithms to prevent brute-force exposures.
* **Contextual Token Injection**: Emits cryptographically signed HMAC256 tokens containing custom membership structures and account statuses.
* **Symmetric Status Translation**: Bridges domain exception vectors into standardized wire-level gRPC `Status` runtime exceptions.

---

## 🛠️ Technology Stack & Runtime Profile
* **Runtime Environment**: Java 25 Engine (Eclipse Temurin Alpine JRE)
* **Core Framework**: Spring Boot 4.0.6 & Spring Data JPA
* **gRPC Framework**: `spring-grpc-server-spring-boot-starter` (v1.0.3)
* **Persistence Layer**: Spring Boot WebMVC / Shared PostgreSQL Engine Dialects
* **Security & Crypto Parsing**: Java-JWT (`com.auth0:java-jwt:4.5.2`) & `jbcrypt` (v0.4)

---

## 📜 gRPC Protocol Buffer Contract Specification

The service implements a strict, pre-compiled wire contract defined in the shared `user-contract` module to handle client authorization states smoothly:

```protobuf
syntax = "proto3";

option java_multiple_files = true;
option java_package = "dev.tunmin.saga.user.grpc";

service UserService {
  // Handles the initial edge login authentication and token generation
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

* **Compilation Rule**: Changes to this schema must be compiled within the `user-contract` module first to update the shared stub dependencies across the cluster space.

---

## 🎛️ Architecture & Communication Flow

```text
       [ api-gateway-service ]
                  │
        (gRPC Channel: 9090)
                  ▼
      [ user-service:9090 ]
        (Spring gRPC Server)
                  │
      ☕ Java 25 Virtual Thread
                  │
         🔏 GrpcExecutor Loop
                  │
         ├─► Repository Lookup ──► [ Find by Email String ]
         │
         ├─► Status Verification ──► [ Enforce Boolean ACTIVE ]
         │
         └─► BCrypt Cross-Check ──► [ Compare Cryptographic Hash ]
                  │
                  ▼
       🛡️ HMAC256 JWT Token Signed
                  │
                  ▼
       [ Emit AuthResponse Stub ]
```

---

## 💾 Predefined Idempotent Test Dataset (`data.sql`)

The database persistence engine loads three structural user profiles on startup via an operational initialization routine:

```sql
-- Target Test Vector Schema Configuration Mapping
INSERT INTO USERS (USER_ID, EMAIL, PASSWORD_HASH, ACTIVE, MEMBERSHIP) 
VALUES (1, 'developer@tunmin.dev', '\$2a\$10\$...', TRUE, 'PREMIUM'); -- User ID 1: Happy Path Target

INSERT INTO USERS (USER_ID, EMAIL, PASSWORD_HASH, ACTIVE, MEMBERSHIP) 
VALUES (2, 'user@tunmin.dev', '\$2a\$10\$...', TRUE, 'STANDARD');   -- User ID 2: Normal Target

INSERT INTO USERS (USER_ID, EMAIL, PASSWORD_HASH, ACTIVE, MEMBERSHIP) 
VALUES (3, 'suspended@tunmin.dev', '\$2a\$10\$...', FALSE, 'STANDARD'); -- User ID 3: Lockout Target
```
*Note: The plaintext credential for all predefined seed entries is exactly `securePassword123`.*

---

## ⚙️ Environment Execution Profiles

The service supports twin operational profiles to manage environment transitions cleanly between host workspaces and containerized planes:

### 1. Default Profile (`default`)
* **Target Layer**: Local, in-memory volatile H2 database infrastructure.
* **Compatibility Metric**: Uses PostgreSQL parsing modes to process high-performance row locks (`FOR UPDATE SKIP LOCKED`) smoothly during simulations.
* **Launch Flag**: Automatically active if no environment variable mappings are provided.

### 2. Kubernetes Pod Profile (`k8sdb`)
* **Target Layer**: Centralized, persistent `postgres:18-alpine` node inside the cluster framework.
* **Segregation Vector**: Dynamically locks read/write scopes to the dedicated schema layer via:
  ```properties
  spring.datasource.url=jdbc:postgresql://postgres-db:5432/saga_db?currentSchema=user_service_schema
  ```
* **Activation Command**: Force using the java system property argument: `-Dspring.profiles.active=k8sdb`.

---

## 🔒 Wire-Level gRPC Exception Translation Matrix

All execution threads run inside a decoupled handling context (`GrpcExecutor`) to prevent sensitive stack details from leaking over the wire plane, converting internal exceptions into standard gRPC `Status` exceptions:

| Internal Business Exception | Emitted gRPC Wire Status | Client Log Reason |
| :--- | :--- | :--- |
| `UserNotFoundException` | `Status.NOT_FOUND` | User database record missing or unknown. |
| `InvalidCredentialsException` | `Status.UNAUTHENTICATED` | Cryptographic password verification mismatch. |
| `UserInactiveException` | `Status.PERMISSION_DENIED` | Profile state is locked or suspended at the border. |
| `IllegalArgumentException` | `Status.INVALID_ARGUMENT` | Inbound payload metrics contain invalid syntax layouts. |
| `Exception` | `Status.INTERNAL` | Unhandled infrastructure or platform failures. |

---

## 📊 Filtered Telemetry & Operational Noise Isolation

The service pushes performance data out of the container using segmented transmission channels. To remain compliant with the strict **5.5GB cluster limit**, it deploys an `ObservationPredicate` bean alongside a unified OpenTelemetry appender loop to strip out routine pipeline noise.

### 1. Dual-Protocol Telemetry Matrix
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
* **Actuator Path Filtering**: The custom `ObservationPredicate` bean scans inbound servlet contexts via `ServerRequestObservationContext` and entirely drops tracking metrics for endpoints matching `/actuator/health` or `/actuator/info` by inspecting the servlet-based `.getCarrier().getRequestURI()` extractor.
* **📜 Unified Appender Setup**: Log streams are automatically forwarded to the central monitoring pipeline over high-efficiency gRPC bridges. The `installOpenTelemetryAppender` routine registers the `OpenTelemetryAppender` natively at boot time to tie application records straight into the active distributed trace context.

