# 🔒 Least-Privilege Cilium Network Policy Manual

This document details the eBPF kernel-level security rules, network boundaries, and Layer 7 visibility layers that sandbox traffic inside the `sample-microservice-saga` project platform.

---

## 🛡️ 1. Security Architecture & Segmented Topology

By default, applying any `CiliumNetworkPolicy` to a workspace transitions Cilium into a strict **Default-Deny** security posture. Traffic is completely blocked across the cluster unless explicitly permitted by an applied policy whitelist rule. 

The ecosystem isolates operational domains into separate functional files under the `manifests/network-policies/` subdirectory to achieve highly targeted permission sweeps:

```text
manifests/network-policies/
├── common-health-probes.yaml          # Safe Kubelet ingress probes using strict L7 HTTP regex filters
├── observability-egress-policy.yaml    # Restricted out-of-namespace metrics, traces, and logging egress
├── api-gateway-service-policy.yaml     # Explicit edge perimeter path routing and backend routing controls
├── order-service-policy.yaml           # Strict state machine ingress and isolated persistence locks
└── user-service-policy.yaml            # Encapsulated binary gRPC ingress from perimeter proxies
```

---

## 🛠️ 2. Core Operational & Shared Policies

### Policy A: Cluster-Wide Health Probes (`common-health-probes.yaml`)
To prevent automated Kubernetes liveness and readiness probe traffic from traveling unvetted across your system, this policy restricts ingress checks exclusively to the underlying workstation Kubelet host. It uses a fine-grained Layer 7 HTTP regular expression filter to drop any payloads that stray outside the health endpoints.

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: health-probes-policy
  namespace: saga-ecosystem
spec:
  endpointSelector:
    matchLabels:
      app.kubernetes.io/part-of: saga-platform
    matchExpressions:
      - key: app.kubernetes.io/component
        operator: In
        values:
          - gateway
          - backend
  ingress:
    - fromEntities:
        - host # Restricts ingress strictly to the node running kubelet checks
      toPorts:
        - ports:
            - port: "8080"
              protocol: TCP
            - port: "8081"
              protocol: TCP
            - port: "8082"
              protocol: TCP
            - port: "8083"
              protocol: TCP
            - port: "8084"
              protocol: TCP
          rules:
            http:
              - method: GET
                path: "^/actuator/health(/liveness|/readiness)?\$" # Matches all standard probe variants
```

### Policy B: Telemetry Channel Safeguards (`observability-egress-policy.yaml`)
This policy grants container components carrying the `component: gateway` and `component: backend` identifiers the narrow authority needed to push system logs, metrics, and transaction traces out of the `saga-ecosystem` space and straight into the OpenTelemetry Collector running in the `monitoring` namespace.

```yaml
apiVersion: cilium.io/v2
kind: CiliumNetworkPolicy
metadata:
  name: observability-egress-policy
  namespace: saga-ecosystem
spec:
  endpointSelector:  
    matchLabels:
      app.kubernetes.io/part-of: saga-platform
    matchExpressions:
      - key: app.kubernetes.io/component
        operator: In
        values:
          - gateway
          - backend
  egress:    
    - toEndpoints:
        - matchLabels:
            app.kubernetes.io/name: otel-collector
            k8s:io.kubernetes.pod.namespace: monitoring
      toPorts:
        - ports:
            - port: "4317"  # Explicit egress path for traces and logs via OTLP/gRPC
              protocol: TCP
            - port: "4318"  # Explicit egress path for performance metrics via OTLP/HTTP
              protocol: TCP
```

---

## ⚡ 3. Microservice Specific Isolation Policies

### Policy C: Perimeter Edge Proxy Rules (`api-gateway-service-policy.yaml`)
The `api-gateway-service` acts as the cluster boundary matching `component: gateway`. It is permitted to receive inbound public requests on port `8080`, but it can only route egress traffic downstream to the specific business components that match its routing tables.

*   **Ingress Path Constraints**: Restricts incoming REST payloads strictly to the matching string regex rules `^/orders$` (POST) and `^/auth/login$` (POST).
*   **Targeted Egress Locks**: Whitelists outbound connections exclusively to port `8081` (`order-service` HTTP REST) and port `9090` (`user-service` high-performance binary gRPC).

### Policy D: Transaction State Machine Separation (`order-service-policy.yaml`)
Protects the core orchestrator component. It explicitly flags and blocks any incoming request that does not originate directly from an endpoint carrying the identity label `app.kubernetes.io/name: api-gateway-service`. 

*   **Persistence Egress**: Whitelists outbound connections on port `5432` to the `postgres` database label, ensuring that the orchestrator can mutate data inside its designated `order_service_schema` while remaining completely isolated from unauthorized network scopes.
*   **Kafka Stream Egress (Testing Notice)**: The egress rules targeting external host-side Kafka brokers via port `9094` remain currently commented out or decoupled inside tracking configurations until explicit FQDN cache behaviors are verified via live event testing loops.

### Policy E: Identity Profile Isolation (`user-service-policy.yaml`)
Secures your authorization provider engine. It allows inbound connection handshakes over high-performance binary gRPC streams via port `9090` strictly when initiated by the `api-gateway-service` perimeter proxy, while whitelisting outbound database connectivity exclusively to your localized PostgreSQL node on port `5432`.

---

## 🟢 4. Sidecarless eBPF Layer 7 Visibility Benefits

Unlike legacy service meshes that inject memory-heavy sidecar proxy containers (like Envoy) into every single pod namespace—which would crash our tight **5.5GB cluster limit**—Cilium operates transparently right inside the Linux host kernel space.

By declaring the explicit metadata annotation `topology.cilium.io/monitored: "true"` inside your service manifests, you instruct Cilium to dynamically hook its kernel filters to your container sockets. This compiles L7 monitoring rules natively, load-balances binary gRPC streams over port `9090` cleanly, and feeds data to your metrics engines without inflating your cluster's RAM footprint.
