# 🔒 Cilium Networking & Least-Privilege Network Policy Manual

This document details the eBPF kernel-level security rules, network boundaries, and Layer 7 visibility layers that sandbox traffic inside the `sample-microservice-saga` project platform.

---

## ⚙️ 1. Cilium Runtime Architecture

This platform uses Cilium as the Kubernetes CNI, providing eBPF-based networking, identity-aware security policies, and service load balancing.

Key enabled capabilities:

- eBPF datapath
- Cilium identity-based security model
- kube-proxy replacement
- eBPF service load balancing
- Cilium Envoy for L7 policy enforcement
- Hubble network observability

### Verify Cilium Runtime

```bash
kubectl -n kube-system exec -it ds/cilium -- cilium-dbg status
```

Expected:

```
KubeProxyReplacement: True
Socket LB: Enabled
Socket LB Coverage: Full
```

Traffic flow:

```text
Application
    |
    v
Cilium eBPF Socket LB
    |
    v
Kubernetes Service abstraction
    |
    v
Backend Pod
```

---

## 🛡️ 2. Security Architecture & Segmented Topology

By applying a `CiliumNetworkPolicy` to selected endpoints, Cilium enables policy enforcement for those workloads. For those endpoints, traffic is denied by default unless explicitly allowed by an applicable ingress or egress rule. 

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

## 🛠️ 3. Core Operational & Shared Policies

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

### Policy C: DNS Resolution Control (`common-dns-egress-policy.yaml`)

All workloads require DNS resolution through the Kubernetes DNS service. 
This policy allows DNS traffic only to the cluster DNS service instead of permitting unrestricted UDP/TCP port 53 egress.

Current implementation:

```yaml
egress:
  - toServices:
      - k8sService:
          namespace: kube-system
          serviceName: kube-dns
    toPorts:
      - ports:
          - port: "53"
            protocol: UDP
          - port: "53"
            protocol: TCP
```
> The policy targets the Kubernetes DNS service abstraction instead of CoreDNS pod endpoints. This avoids coupling DNS access rules to individual CoreDNS pod identities.

---

## ⚡ 4. Microservice Specific Isolation Policies

### Policy D: Perimeter Edge Proxy Rules (`api-gateway-service-policy.yaml`)
The `api-gateway-service` acts as the cluster boundary matching `component: gateway`. It is permitted to receive inbound public requests on port `8080`, but it can only route egress traffic downstream to the specific business components that match its routing tables.

*   **Ingress Path Constraints**: Restricts incoming REST payloads strictly to the matching string regex rules `^/orders$` (POST) and `^/auth/login$` (POST).
*   **Targeted Egress Locks**: Whitelists outbound connections exclusively to port `8081` (`order-service` HTTP REST) and port `9090` (`user-service` high-performance binary gRPC).

### Policy E: Transaction State Machine Separation (`order-service-policy.yaml`)
Protects the core orchestrator component. It explicitly flags and blocks any incoming request that does not originate directly from an endpoint carrying the identity label `app.kubernetes.io/name: api-gateway-service`. 

*   **Persistence Egress**: Whitelists outbound connections on port `5432` to the `postgres` database label, ensuring that the orchestrator can mutate data inside its designated `order_service_schema` while remaining completely isolated from unauthorized network scopes.
*   **Kafka Stream Egress**: Kafka connectivity has been verified through Cilium flow observation. Current enforcement is L3/L4 TCP port-based (`9094`). Kafka-aware L7 policies are not currently implemented.

### Policy F: Identity Profile Isolation (`user-service-policy.yaml`)
Secures your authorization provider engine. It allows inbound connection handshakes over high-performance binary gRPC streams via port `9090` strictly when initiated by the `api-gateway-service` perimeter proxy, while whitelisting outbound database connectivity exclusively to your localized PostgreSQL node on port `5432`.

---

## 🔭 5. Hubble Network Observability

Hubble is Cilium's observability layer and provides real-time network flow visibility from the eBPF datapath.

Enable:

```bash
cilium hubble enable --ui
```
> Run from a machine where the Cilium CLI is installed and connected to the Kubernetes cluster.

Verify components:
```bash
kubectl -n kube-system get pods | findstr hubble
```

Observe live flows:
```bash
kubectl -n kube-system exec -it ds/cilium -- hubble observe --follow --from-pod saga-ecosystem/api-gateway-service-xxxxx
```

Example verified application flow:
```bash
api-gateway-service -> user-service:9090
api-gateway-service -> order-service:8081
```

---

## 🟢 6. Sidecarless eBPF Layer 7 Visibility Benefits

Cilium provides Layer 7 visibility by applying L7-aware network policies. Selected traffic is transparently redirected through Cilium Envoy for protocol-aware inspection and enforcement.

Unlike traditional service meshes that require sidecar proxy injection into every workload, Cilium provides networking and security capabilities directly through the eBPF datapath, reducing operational and memory overhead in resource-constrained environments.

---

## 🧪 7. Policy Verification

Network policies are verified using application tests and Hubble flow inspection.

### Allowed Traffic

Example:

```text
api-gateway-service
        |
        | gRPC :9090
        v
user-service
```

Verify:
```bash
kubectl -n kube-system exec -it ds/cilium -- hubble observe --follow --from-pod saga-ecosystem/api-gateway-service-xxxxx
```

Expected:
```text
api-gateway-service <> user-service:9090 TRACED (TCP)
```

The TRACED verdict confirms that Cilium observed the traffic through the eBPF datapath. A successful allowed connection will also show a FORWARDED verdict.

> Additional verification can be performed by testing unauthorized connections and confirming `DROPPED` verdicts in Hubble.
