# MeshItUp — Deployment Guide

Complete guide for deploying MeshItUp service mesh to local Kubernetes.

---

## 1. Prerequisites

### macOS — OrbStack (Recommended)

[OrbStack](https://orbstack.dev) provides Docker + Kubernetes in one, faster than Docker Desktop.

1. Install OrbStack:
   ```bash
   brew install orbstack
   ```

2. Enable Kubernetes in Settings → Kubernetes → Enable.

3. Verify context:
   ```bash
   kubectl config current-context
   # expected: orbstack
   ```

### Linux / Windows

Use any local k8s: `kind`, `minikube`, `k3d`, or Docker Desktop with Kubernetes.

### Required Tools

| Tool | Purpose | macOS Install |
|---|---|---|
| `kubectl` | Cluster management | `brew install kubectl` |
| `docker` | Image builds | included with OrbStack |
| `mvn` (Maven 3.9+) | Java builds | `brew install maven` |
| `java` (JDK 21) | Java compilation | `brew install openjdk@21` |
| `hey` | Load testing | `brew install hey` |

**Go is not required** — Go services build via multi-stage Docker builds.

---

## 2. Project Structure

```
MeshItUp/
├── control-plane/
│   ├── Dockerfile
│   ├── pom.xml
│   ├── src/
│   └── k8s/
│       ├── 00-namespace.yaml
│       ├── 01-configmap.yaml
│       ├── 02-rbac.yaml
│       ├── 03-deployment.yaml
│       └── 04-service.yaml
├── sidecar-proxy-new/
│   ├── Dockerfile
│   ├── pom.xml
│   ├── src/
│   └── k8s/
│       ├── api-gateway.yaml
│       └── inventory-service.yaml
├── go-services/
│   ├── api-gateway/
│   │   └── Dockerfile
│   └── inventory-service/
│       └── Dockerfile
└── k8s-monitoring/
    ├── prometheus/
    │   ├── 01-rbac.yaml
│   ├── 02-configmap.yaml
│   └── 03-deployment.yaml
    └── grafana/
        ├── 01-datasource.yaml
        ├── 02-dashboard-provider.yaml
        ├── 03-dashboard.yaml
        └── 04-deployment.yaml
```

---

## 3. Build Docker Images

> **Note:** OrbStack (like Docker Desktop) uses a shared Docker daemon. Images built with `docker build` are immediately available to Kubernetes — no push required. All manifests use `imagePullPolicy: IfNotPresent`.

### 3.1 API Gateway (Go)

```bash
cd go-services/api-gateway
docker build -t api-gateway:latest .
cd ../..
```

### 3.2 Inventory Service (Go)

```bash
cd go-services/inventory-service
docker build -t inventory-service:latest .
cd ../..
```

### 3.3 Sidecar Proxy (Java)

```bash
cd sidecar-proxy-new
mvn clean package -DskipTests
docker build -t mesh-sidecar:latest .
cd ..
```

### 3.4 Control Plane (Java)

```bash
cd control-plane
mvn clean package -DskipTests
docker build -t mesh-control-plane:latest .
cd ..
```

### 3.5 Verify Images

```bash
docker images | grep -E "api-gateway|inventory-service|mesh-sidecar|mesh-control-plane"
```

Expected output:
```
api-gateway            latest    ...    ~15MB
inventory-service      latest    ...    ~15MB
mesh-sidecar           latest    ...    ~250MB
mesh-control-plane     latest    ...    ~250MB
```

---

## 4. Deploy to Kubernetes

**Order matters:** namespace/config first, then control-plane (so sidecars can fetch config on startup), then business services, then monitoring.

### 4.1 Control Plane

```bash
kubectl apply -f control-plane/k8s/
```

Wait for readiness:
```bash
kubectl -n mesh wait --for=condition=ready pod -l app=mesh-control-plane --timeout=120s
```

Verify control plane loaded config:
```bash
kubectl -n mesh port-forward svc/mesh-control-plane 8080:8080 &
curl http://localhost:8080/api/v1/services
# Expected: [{"id":"api-gateway",...},{"id":"inventory-service",...}]
kill %1
```

### 4.2 Business Services (with Sidecars)

```bash
kubectl apply -f sidecar-proxy-new/k8s/
```

### 4.3 Monitoring

```bash
kubectl apply -f k8s-monitoring/prometheus/
kubectl apply -f k8s-monitoring/grafana/
```

Grafana dashboard auto-provisions via `03-dashboard.yaml`.

### 4.4 Verify Deployment

```bash
kubectl -n mesh get pods
```

Expected output:
```
NAME                                  READY   STATUS    RESTARTS   AGE
api-gateway-xxxxxxxxxx-xxxxx          2/2     Running   0          1m
grafana-xxxxxxxxxx-xxxxx              1/1     Running   0          1m
inventory-service-xxxxxxxxxx-xxxxx    2/2     Running   0          1m
mesh-control-plane-xxxxxxxxxx-xxxxx   1/1     Running   0          2m
prometheus-xxxxxxxxxx-xxxxx           1/1     Running   0          1m
```

---

## 5. Port Forwarding

Create `port-forward.sh` in project root:

```bash
#!/usr/bin/env bash
set -e

echo "Starting port-forwards..."

kubectl -n mesh port-forward svc/api-gateway         9090:8080 > /dev/null 2>&1 &
echo "  api-gateway        → http://localhost:9090"

kubectl -n mesh port-forward svc/mesh-control-plane  8081:8080 > /dev/null 2>&1 &
echo "  control-plane      → http://localhost:8081"

kubectl -n mesh port-forward svc/prometheus          9091:9090 > /dev/null 2>&1 &
echo "  prometheus         → http://localhost:9091"

kubectl -n mesh port-forward svc/grafana             3000:3000 > /dev/null 2>&1 &
echo "  grafana            → http://localhost:3000"

echo ""
echo "All port-forwards running in background."
echo "To stop them all: pkill -f 'kubectl.*port-forward'"
```

Make executable and run:
```bash
chmod +x port-forward.sh
./port-forward.sh
```

### Access URLs

| Service | URL | Purpose |
|---|---|---|
| API Gateway | http://localhost:9090 | Entry point |
| Control Plane | http://localhost:8081 | REST API + Swagger UI |
| Prometheus | http://localhost:9091 | Metrics |
| Grafana | http://localhost:3000 | Dashboards (admin/admin) |

Stop all: `pkill -f 'kubectl.*port-forward'`

---

## 6. Smoke Test

Verify the chain `api-gateway → sidecar → inventory-service` works:

```bash
curl -v http://localhost:9090/api/inventory
```

Expected: HTTP 200 with JSON response from inventory-service.

---

## 7. Load Testing with `hey`

Generate load to see metrics on Grafana dashboards:

```bash
hey -q 4 -c 5 -z 5m http://localhost:9090/api/inventory
```

Parameters:
- `-q 4` — 4 requests/second per worker
- `-c 5` — 5 parallel workers
- `-z 5m` — duration 5 minutes

Total: **20 rps for 5 minutes** — enough to see stable metrics.

Other presets:
```bash
# Short burst: 100 requests in 10 threads
hey -n 100 -c 10 http://localhost:9090/api/inventory

# Constant 50 rps for 1 minute
hey -q 10 -c 5 -z 1m http://localhost:9090/api/inventory
```

---

## 8. Grafana Monitoring

1. Open http://localhost:3000
2. Login: `admin` / `admin`
3. Prometheus datasource auto-configured
4. Dashboard: **Dashboards → Browse → Service Mesh Dashboard**

Panels:
- **RPS by version** — requests/sec split by stable/canary
- **Error Rate** — error percentage
- **Latency p95** — 95th percentile response time
- **Retries** — retry attempts
- **Traffic** — traffic distribution between versions
- **HTTP Status Codes** — 2xx / 4xx / 5xx

---

## 9. Control Plane API (Swagger)

Swagger UI: http://localhost:8081/swagger-ui.html

### Key Endpoints

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/v1/config?serviceId=api-gateway` | Config for sidecar |
| `GET` | `/api/v1/services` | Registered services |
| `GET` | `/api/v1/routes` | Current routes with weights |
| `POST` | `/api/v1/config/reload` | Reload mesh-config.yaml |
| `POST` | `/api/v1/canary/start` | Start canary release |
| `GET` | `/api/v1/canary/status` | Canary status |
| `POST` | `/api/v1/canary/promote` | Manual promote |
| `POST` | `/api/v1/canary/rollback` | Manual rollback |

### Example Requests

```bash
# Check current config
curl http://localhost:8081/api/v1/config?serviceId=api-gateway

# View active routes
curl http://localhost:8081/api/v1/routes
```

---

## 10. Canary Release Scenarios

### 10.1 Successful Canary (Full Cycle)

Launch canary with `FAULT_RATE=0`. Control plane gradually increases weight to 100% and promotes.

```bash
curl -X POST http://localhost:8081/api/v1/canary/start \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "inventory-service",
    "canaryImage": "inventory-service:latest",
    "canaryEnv": {
      "VERSION": "v2",
      "FAULT_RATE": "0",
      "PORT": "8080"
    },
    "initialWeight": 10,
    "weightStep": 10,
    "errorThreshold": 5.0
  }'
```

Monitor:
```bash
# Watch canary status every 30 seconds
curl http://localhost:8081/api/v1/canary/status | jq .

# Check traffic distribution
for i in $(seq 1 30); do
  curl -s http://localhost:9090/api/inventory | jq -r '.version'
done | sort | uniq -c
# Expected: ~27 v1, ~3 v2 (90%/10%)
```

After promote (weight=100%):
- All requests return `version: v2`
- Canary pod deleted
- Routes restored: stable=100%

### 10.2 Automatic Rollback

Launch canary with `FAULT_RATE=20` — 20% of requests return HTTP 500.

```bash
curl -X POST http://localhost:8081/api/v1/canary/start \
  -H "Content-Type: application/json" \
  -d '{
    "serviceId": "inventory-service",
    "canaryImage": "inventory-service:latest",
    "canaryEnv": {
      "VERSION": "v2-faulty",
      "FAULT_RATE": "20",
      "PORT": "8080"
    },
    "initialWeight": 10,
    "weightStep": 10,
    "errorThreshold": 5.0
  }'
```

Generate traffic:
```bash
for i in $(seq 1 200); do
  curl -s -o /dev/null -w "%{http_code}\n" http://localhost:9090/api/inventory
  sleep 0.3
done
```

Expected: Within 30-60 seconds, evaluator detects error rate > 5% and rolls back.

```bash
curl http://localhost:8081/api/v1/canary/status | jq .
# {"status": "ROLLED_BACK", "lastEvaluationResult": "ROLLBACK: canary error rate 20.00% > threshold 5.00%"}
```

### 10.3 Manual Control

```bash
# Manual promote (immediate)
curl -X POST http://localhost:8081/api/v1/canary/promote

# Manual rollback (immediate)
curl -X POST http://localhost:8081/api/v1/canary/rollback
```

---

## 11. Cleanup

### Stop Port Forwards
```bash
pkill -f 'kubectl.*port-forward'
```

### Delete All Resources
```bash
kubectl delete namespace mesh
```

### Remove Local Images (Optional)
```bash
docker rmi api-gateway:latest inventory-service:latest inventory-service:v2 \
           mesh-sidecar:latest mesh-control-plane:latest
```

---

## 12. Troubleshooting

### Pods in `ImagePullBackOff` or `ErrImagePull`

Kubernetes can't find local image. Verify:
```bash
docker images | grep -E "api-gateway|inventory-service|mesh-sidecar|mesh-control-plane"
```

If missing, rebuild. Manifests should have `imagePullPolicy: IfNotPresent`.

### Pod in `CrashLoopBackOff`

Check logs:
```bash
kubectl -n mesh logs <pod-name>
```

For pods with 2 containers:
```bash
# Main service logs
kubectl -n mesh logs <pod-name> -c api-gateway

# Sidecar logs
kubectl -n mesh logs <pod-name> -c mesh-sidecar
```

### Sidecar: "Failed to fetch config from control plane"

Control plane not ready yet. Check:
```bash
kubectl -n mesh get pods -l app=mesh-control-plane
kubectl -n mesh logs -l app=mesh-control-plane
```

Sidecars work with cached config, so these warnings are normal on first startup.

### `curl` returns `Connection refused`

Port-forward not running. Check:
```bash
ps aux | grep 'kubectl.*port-forward'
```

Restart: `./port-forward.sh`

### `curl` returns 502/503

API Gateway can't reach Inventory Service. Verify both pods are `2/2 Running`:
```bash
kubectl -n mesh get pods
```

If OK, check sidecar logs at api-gateway for HTTP client errors.

### Ports Already in Use

If `9090`, `8081`, `9091`, or `3000` are busy, change the left side in `port-forward.sh`.

### Grafana: "No data" on all panels

1. Ensure load is running (`hey`)
2. Check Prometheus targets: http://localhost:9091/targets — all should be `UP`
3. If targets not `UP`, verify `prometheus.io/scrape: "true"` annotations on sidecar pods

### Full Rebuild from Scratch

```bash
kubectl delete namespace mesh
docker rmi api-gateway:latest inventory-service:latest mesh-sidecar:latest mesh-control-plane:latest
# Rebuild images (section 3) and redeploy (section 4)
```

---

## Quick Reference

```bash
# 1. Build all images
docker build -t api-gateway:latest ./go-services/api-gateway
docker build -t inventory-service:latest ./go-services/inventory-service
docker build -t mesh-sidecar:latest ./sidecar-proxy-new
docker build -t mesh-control-plane:latest ./control-plane

# 2. Deploy
kubectl apply -f control-plane/k8s/
kubectl apply -f sidecar-proxy-new/k8s/
kubectl apply -f k8s-monitoring/

# 3. Port forward
./port-forward.sh

# 4. Test
curl http://localhost:9090/api/inventory

# 5. Start canary
curl -X POST http://localhost:8081/api/v1/canary/start \
  -H "Content-Type: application/json" \
  -d '{"serviceId":"inventory-service","canaryImage":"inventory-service:latest","canaryEnv":{"VERSION":"v2","FAULT_RATE":"0"},"initialWeight":10,"weightStep":10,"errorThreshold":5.0}'

# 6. Cleanup
kubectl delete namespace mesh
```
