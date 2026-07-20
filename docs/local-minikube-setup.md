# 🚀 Local Minikube Cluster Provisioning & Setup Guide

This manual details the step-by-step technical runbook to initialize your local workstation virtualization platform layer, compile your multi-module Java 25 microservice images internally, and bootstrap your segregated multi-tenant persistence schema rules.

---

## 🛠️ Phase 1: Cluster Provisioning & eBPF Core Initialization

To maintain maximum compatibility with modern Linux kernel eBPF socket operations on Windows, the cluster is spun up via the bare-metal Hyper-V Type-1 Hypervisor driver.

### Step 1: Initialize the Minikube Node Interface
Run these commands from an **Administrative terminal window** on your host workstation to clear out old contexts and provision strict computing resource boundaries:

```bash
# 1. Clear old under-powered configuration contexts completely
minikube delete

# 2. Initialize fresh hypervisor VM with an empty network canvas
# NOTE: We utilize --cni=false to bypass the Windows path-validation bug found in --cni=none
minikube start --driver=hyperv --memory=5632 --cpus=3 --cni=false
```
* `--cni=false`: Explicitly prevents default, resource-heavy default networking profiles, leaving an open network interface engine for Cilium to attach natively to the underlying Linux kernel.

#### Step 1.5: Install the Cilium CLI Tooling on Your Workstation Host

Before deploying the mesh engine or running network audits, you must install the official Cilium CLI binary onto your local workstation host machine. This grants your terminal direct administrative access to control and inspect the cluster's underlying eBPF network planes and Hubble flow streams.

Select the installation block corresponding to your workstation Operating System:

##### 💻 Windows 11 Pro (PowerShell)
Open an **Administrative PowerShell** window and run:
```powershell
# 1. Download the latest stable Windows 64-bit Cilium executable binary
curl.exe -L --fail --remote-name https://github.com

# 2. Extract the zipped archive content natively
tar -xzvf .\cilium-windows-amd64.tar.gz

# 3. Clean up the downloaded archive layer
Remove-Item .\cilium-windows-amd64.tar.gz
```
> ⚙️ **SYSTEM PATH CONFIGURATION**: Move the extracted `cilium.exe` file into a dedicated infrastructure folder (e.g., `C:\src\infra\`) and ensure this folder path is appended to your Windows System Environment **PATH** Variable. Restart your terminal to refresh the environment variables.

##### 🍎 macOS (Terminal)
Open your terminal shell window and leverage the Homebrew packaging provider, or compile the stable binary directly:
```bash
# Option A: Install via Homebrew package manager
brew install cilium-cli

# Option B: Manual stable direct binary download (Auto-detects architecture)
CILIUM_CLI_VERSION=\$(curl -s https://githubusercontent.com)
CLI_ARCH=amd64
if [ "\$(uname -m)" = "arm64" ]; then CLI_ARCH=arm64; fi
curl -L --fail --remote-name "https://github.com\({CILIUM_CLI_VERSION}/cilium-darwin-\){CLI_ARCH}.tar.gz"
tar -xzvf cilium-darwin-\${CLI_ARCH}.tar.gz
sudo mv cilium /usr/local/bin
rm cilium-darwin-\${CLI_ARCH}.tar.gz
```

##### 🐧 Linux Distributions (Bash)
Open your Linux terminal shell and pull the architecture-matched release binary:
```bash
# Download and extract the stable Linux binary engine
CILIUM_CLI_VERSION=\$(curl -s https://githubusercontent.com)
CLI_ARCH=amd64
if [ "\$(uname -m)" = "aarch64" ]; then CLI_ARCH=arm64; fi
curl -L --fail --remote-name "https://github.com\({CILIUM_CLI_VERSION}/cilium-linux-\){CLI_ARCH}.tar.gz"
tar -xzvf cilium-linux-\${CLI_ARCH}.tar.gz
sudo mv cilium /usr/local/bin
rm cilium-linux-\${CLI_ARCH}.tar.gz
```

##### 🧪 Verification Check
After configuring your paths and restarting your current shell instance, execute a simple version query from any location to confirm that your workstation host interface has unlocked active communication lines to Cilium:

```bash
cilium version
```

### Step 2: Clean and Deploy the Cilium Mesh Engine
Since `minikube delete` completely destroys the virtual machine environment, running `cilium.exe uninstall` is not required. Move straight to the inline cluster setup phase.

Execute the installation using Cilium's embedded manifests to override loop network boundaries and enable core metrics gathering infrastructure hooks:

```cmd
cilium.exe install --helm-set prometheus.enabled=true --helm-set operator.prometheus.enabled=true --helm-set gatewayAPI.enabled=true
```

> 💡 **Gateway API & L7 Routing Note:** Passing `--helm-set gatewayAPI.enabled=true` instructs the Cilium operator to automatically pull and provision standard Kubernetes Gateway API schemas. Because our microservices rely on sidecarless gRPC communications, activating this property ensures Cilium compiles the necessary eBPF kernel filters and boots its embedded Envoy proxy (`cilium-envoy`) daemonset to handle transparent Layer 7 gRPC load balancing, path routing, and Prometheus infrastructure metrics out-of-the-box.

### Step 3: Verify Core eBPF Network Health
Monitor the container runtime until all primary eBPF daemonsets transition to an active running status profile.

```cmd
cilium.exe status --wait
```
*Note: `cilium-envoy` may take an extra minute to transition from Pending to Running while Hyper-V initializes the background image layer.*

---

## 🗂️ Phase 2: Core Namespace & Configuration Provisioning

All business services reside inside the `saga-ecosystem` block, while monitoring infrastructure is isolated inside the `monitoring` namespace. Ensure you execute the configuration sequence exactly in this file order from your repository root:

```cmd
# Navigate into your local manifest registry folder
cd manifests

# 1. Establish namespaces (with Cilium monitoring labels)
kubectl apply -f namespace-setup.yaml

# 2. Inject global cluster configuration parameters (KAFKA_SERVERS, ports, etc.)
kubectl apply -f cluster-configmap.yaml

# 3. Apply secure base64 credential data contexts
kubectl apply -f ecosystem-secrets.yaml
```

---

## 💾 Phase 3: Multi-Tenant Database Layer Setup

To optimize memory constraints under our strict 5.5GB hypervisor boundary, a single PostgreSQL instance houses isolated Schema Blocks instead of heavy, individual database engines.

### Step 1: Deploy the Central Database Engine
Run this command from your terminal to deploy your instance:

```cmd
kubectl apply -f postgres-manifestst.yaml
```

### Step 2: Validate Schema Ingress Stability
Confirm the pod reaches a stable `1/1 Running` status and verify that port `5432` is bound safely via the NodePort rule across your cluster IP space:

```cmd
# Track node status
kubectl get pods -n saga-ecosystem

# Confirm service nodePort routing allocation (maps externally to 30432)
kubectl get svc postgres-db -n saga-ecosystem
```

---

## 📦 Phase 4: Application Compilation & Native In-Cluster Builds

To eliminate cross-hypervisor file locking resource conflicts on your workstation, we bypass host-level image transfers entirely. By linking your terminal directly into Minikube, images are compiled natively straight inside the cluster's internal storage layer with Docker Desktop stopped.

### Step 1: Compile the Microservice Binaries via your IDE
Package your multi-module source code into clean execution artifacts from your root project terminal window:

```cmd
mvnw.cmd clean package -DskipTests
```

### Step 2: Link Your Windows Shell to Minikube's Docker Engine
Ensure Minikube is active and Docker Desktop is completely shut down. Execute this command block to direct your standard `docker` CLI commands straight inside the cluster virtual disk:

```cmd
@FOR /f "tokens=*" %i IN ('minikube -p minikube docker-env') DO @%i
```
*Verification: Run `docker images`. You should see your internal cluster node control-plane containers instead of an empty host system registry.*

### Step 3: Run the In-Cluster Compilation Loop
Execute your container builds. Because your terminal context is linked, your images compile natively inside the Hyper-V virtual disk context. Each service relies on the presence of a valid `Dockerfile` inside its directory root to structure its multi-stage extraction layers and bake in your Java 2 production optimizations:

```cmd
docker build -t dev.tunmin.saga/order-service:1.0.0-rc1 ./order-service
docker build -t dev.tunmin.saga/payment-service:1.0.0-rc1 ./payment-service
docker build -t dev.tunmin.saga/restaurant-service:1.0.0-rc1 ./restaurant-service
docker build -t dev.tunmin.saga/user-service:1.0.0-rc1 ./user-service
docker build -t dev.tunmin.saga/api-gateway-service:1.0.0-rc1 ./api-gateway-service
```
> 🚀 **Note:** You can completely skip the `minikube image load` step. Your images are already stored inside the cluster registry and ready for instant deployment.

---

## 🎛️ Phase 5: Workstation Network Port-Forward Tunnel Mesh

Because Minikube operates inside an isolated Hyper-V virtual subnet interface, your bare-metal Windows host environment cannot query or stream data straight to cluster-internal IP networks. The repository provides two distinct batch script utilities to automatically spin up localized proxy bridges using background `kubectl port-forward` worker loops.

### 💻 Scenario A: Hybrid Local IDE Development Loop ([`start-infra-bridge.bat`](../start-infra-bridge.bat))
Execute this script when running and debugging your microservices locally inside your host IDE (e.g., IntelliJ or Eclipse) while using the cluster strictly for tracking backends and telemetry persistence.

```cmd
@echo off
echo =====================================================================
echo STARTING MINIKUBE HYBRID INFRASTRUCTURE TUNNEL FOR IDE RUNTIME
echo =====================================================================

echo [1/4] Setting namespace context...
kubectl config set-context --current --namespace=saga-ecosystem

echo [2/4] Spawning OpenTelemetry ingestion streams...
start "OTel-gRPC-4317" kubectl port-forward --address 127.0.0.1 svc/otel-collector -n monitoring 4317:4317
start "OTel-HTTP-4318" kubectl port-forward --address 127.0.0.1 svc/otel-collector -n monitoring 4318:4318

echo [3/4] Spawning visualization dashboard tunnel...
start "Grafana-UI-3000" kubectl port-forward --address 127.0.0.1 svc/grafana -n monitoring 3000:3000

echo [4/4] Infrastructure bridge initialized successfully!
echo KEEP THE NEW MINIMIZED WINDOWS RUNNING IN THE BACKGROUND
pause
```

### ☸️ Scenario B: In-Cluster Container Verification Loop ([`start-infra-bridge-k8s.bat`](../scripts/start-infra-bridge-k8s.bat))
Execute this script when all business microservices have been successfully built as container layers and deployed straight into the live cluster node context.

```cmd
@echo off
echo =====================================================================
echo STARTING MINIKUBE KUBERNETES CONTAINER TESTING TUNNEL MESH
echo =====================================================================

echo [1/5] Locking context scope to 'saga-ecosystem'...
kubectl config set-context --current --namespace=saga-ecosystem

echo [2/5] Spawning Edge Ingress Proxy Entrypoint (API Gateway)...
start "API-Gateway-8080" kubectl port-forward --address 127.0.0.1 svc/api-gateway-service -n saga-ecosystem 8080:8080

echo [3/5] Spawning Telemetry Core Processing Engines...
start "PostgreSQL-5432" kubectl port-forward --address 127.0.0.1 svc/postgres-db -n saga-ecosystem 5432:5432

echo [4/5] Spawning Visualization Dashboard Tunnel...
start "Grafana-UI-3000" kubectl port-forward --address 127.0.0.1 svc/grafana -n monitoring 3000:3000

echo [5/5] In-Cluster Verification Bridge Initialized Successfully!
echo ---------------------------------------------------------------------
echo ACCESS URLS FOR CONTAINER PERFORMANCE VERIFICATION:
echo  - API Gateway Base:   http://localhost:8080
echo  - Grafana Dashboard: http://localhost:3000
echo  - Database Target:   localhost:5432
echo ---------------------------------------------------------------------
echo KEEP THE NEW MINIMIZED WINDOWS RUNNING IN THE BACKGROUND
pause
```

> ⚠️ **OPERATIONAL SAFETYS**: Always leave the newly spawned, minimized command windows running in your host taskbar background while troubleshooting. Closing them destroys the Layer 4 proxy listeners, causing instant application connection failures.
