# 🧯 Windows Hypervisor Environment Troubleshooting Guide

This troubleshooting guide details the technical playbooks required to recover your local development workspace from workstation anomalies, hypervisor adapter drift following Windows Updates, and loopback socket routing locks.

---

## 🚨 1. Shifting Hyper-V NAT Subnets After Windows Updates

### Symptoms
Following a Windows Cumulative Update and workstation restart, all Kafka-bound microservices (`order`, `payment`, `restaurant`) drop into an aggressive `CrashLoopBackOff` loop. Container logs output a Layer 4 socket setup timeout after roughly 10 seconds:
```text
Disconnecting from node -1 due to socket connection setup timeout. The timeout value is 10061 ms.
```

### True Root Cause
Windows Cumulative Updates frequently delete and rebuild the underlying **Hyper-V Default Switch virtual network drivers**. When Minikube boots back up, it attaches to a completely fresh, randomized internal NAT gateway subnet (e.g., shifting dynamically into `172.27.224.x` spaces). 

While Minikube updates its internal `/etc/hosts` table dynamically, your host-side Kafka broker or local Windows Firewall profile scopes continue to drop or reject traffic originating from the freshly assigned virtual adapter subnet interfaces.

### Flawless, Static Recovery Runbook
You do **NOT** need to hardcode dynamic IPs into your microservice code configurations or manually update your repository config maps. Follow this sequence to restore full automation:

1. **Verify the Live Host Gateway Route**: Log straight into your virtual node to confirm what numeric IP address Minikube is routing through for your Windows host:
   ```bash
   minikube ssh "ip route show default"
   ```
   * *Output Example:* `default via 172.27.224.1 dev eth0 proto dhcp src 172.27.233.42`
   * The IP address directly following `via` is your active post-update Windows host interface gateway.

2. **Probe the Raw Interface**: Confirm that the virtual machine can clear your workstation OS layers and ping your host's Kafka port directly:
   ```bash
   minikube ssh "curl -v http://172.27.224.1:9094"
   ```
   * If this returns `* Empty reply from server`, the network card path is completely open and Kafka is listening! Move directly to Step 3.

3. **Flush the In-Cluster Name Server Caches**: Minikube's CoreDNS pods heavily cache your pre-update network mappings. Force a rollout restart of the name server deployment to sync `host.minikube.internal` to your active gateway IP string:
   ```bash
   kubectl rollout restart deployment coredns -n kube-system
   ```

4. **Clear Stale Connection Timers**: Reset your business deployments to drop the aggressive back-off penalty loops and initialize fresh connection handshakes:
   ```bash
   kubectl scale deployment order-service payment-service restaurant-service --replicas=0 -n saga-ecosystem
   kubectl scale deployment order-service payment-service restaurant-service --replicas=1 -n saga-ecosystem
   ```

---

## 🔒 2. Windows Defender Firewall Inbound Blockage

### Symptoms
The `minikube ssh` curl test to your host gateway IP address hangs indefinitely or returns a explicit `Connection timed out` or `No route to host` flag, despite having an active firewall rule configured for port `9094`.

### True Root Cause
When you initially provisioned your inbound firewall rule for Kafka port `9094`, Windows assigned the rule strictly to your active "Public" or "Private" network interface profile. When the post-update Hyper-V virtual switch is generated, Windows often categorizes that fresh virtual network interface as a "Domain" or unmapped profile space, causing the firewall to silently drop the incoming container packets.

### Recovery Command
Force your inbound developer rule to apply universally across **any** inbound connection profile group (Domain, Public, and Private) to ensure your virtual adapters pass traffic smoothly. Open an **Administrative PowerShell** window on your Windows machine and execute:

```powershell
Set-NetFirewallRule -DisplayName "Saga Dev Kafka Update Patch" -Profile Any -RemoteAddress Any
```

---

## 🔀 3. Local IDE vs Cluster Port Binding Mismatches

### Symptoms
The application `AdminClient` initiates and prints `bootstrap.servers = [host.minikube.internal:9094]` fine in your initial console logs, but downstream event streaming channels throw a loop block error trying to find a broker on `localhost:9092`.

### True Root Cause
Your host-side Kafka `server.properties` segments traffic using isolated, hardcoded security listener protocol arrays to allow concurrent bare-metal and cluster pod execution:
```properties
listeners=INTERNAL://127.0.0.1:9092,EXTERNAL://0.0.0.0:9094
```
If your `application.yaml` files have a port assignment mismatch or fail to parse your independent environmental tags (`${KAFKA_SERVERS}:${KAFKA_PORT}`), Spring's stream binder namespaces default to port `9092`. Because port `9092` is locked exclusively to your host's local loopback memory interface (`127.0.0.1`), it completely rejects external virtualization traffic coming out of the Minikube NAT bridge, causing your connection to hang.

### Structural Alignment Check
Ensure your multi-module files mirror these explicit patterns to remain completely flexible across execution layers:

* **Microservice `application.yaml` Syntax**:
  ```yaml
  spring:
    kafka:
      bootstrap-servers: \({KAFKA_SERVERS:host.minikube.internal}:\){KAFKA_PORT:9092}
  ```
* **Kubernetes `cluster-configmap.yaml` Layout**:
  ```yaml
  data:
    KAFKA_SERVERS: "host.minikube.internal"
    KAFKA_PORT: "9094"
  ```
  *(Always ensure you run `kubectl apply -f manifests/cluster-configmap.yaml -n saga-ecosystem` followed by a deployment bounce right after updating these keys).*
