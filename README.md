# MeshItUp — Educational Service Mesh Implementation

**MeshItUp** is a complete service mesh implementation developed as a coursework project for BSU Faculty of Applied Mathematics on "Service Mesh: Capabilities and Implementation".

## 🎯 Purpose

Build a fully functional service mesh from scratch that provides:
- **Traffic management** — weighted routing between service versions (stable/canary)
- **Canary releases** — automated gradual rollout with health evaluation
- **Automatic rollback** — detect failing canaries and revert automatically
- **Retry policies** — handle transient failures transparently
- **Observability** — Prometheus metrics + Grafana dashboards
- **Zero code changes** — business services are unaware of the mesh

## 🏗️ Architecture

```
User Request
     │
     ▼
┌────────────────────────────────────────────┐
│  API Gateway Pod                           │
│  ┌──────────────┐  ┌──────────────────┐   │
│  │ api-gateway  │→ │ mesh-sidecar     │   │
│  │  (Go :8080)  │  │ (Java :15001)    │   │
│  └──────────────┘  └──────┬───────────┘   │
└───────────────────────────┼────────────────┘
                            │ routes from Control Plane
                            ▼
                    ┌───────────────────┐
                    │  Router           │
                    │  weighted random  │
                    └───────┬───────────┘
                            │
        ┌───────────────────┴───────────────────┐
        │                                       │
        ▼ (90%)                                 ▼ (10%)
┌────────────────────────────────┐  ┌────────────────────────────────┐
│  Inventory Service (stable)    │  │  Inventory Service (canary)    │
│  ┌──────────────┐ ┌──────────┐│  │  ┌──────────────┐ ┌──────────┐│
│  │ inventory-   │ │ sidecar  ││  │  │ inventory-   │ │ sidecar  ││
│  │ service :8080│ │ :15001   ││  │  │ service :8080│ │ :15001   ││
│  └──────────────┘ └──────────┘│  │  └──────────────┘ └──────────┘│
│  version: v1                   │  │  version: v2                   │
└────────────────────────────────┘  └────────────────────────────────┘
         │                                       │
         └───────────────────┬───────────────────┘
                             │
                             ▼ metrics reports
                    ┌─────────────────┐
                    │  Control Plane  │
                    │  - evaluates    │
                    │  - promotes     │
                    │  - rolls back   │
                    └─────────────────┘
```

## 📦 Components

| Component | Technology | Description | Docs |
|---|---|---|---|
| **Control Plane** | Java 21, Spring Boot | Centralized config management, canary lifecycle orchestration, metrics aggregation | [control-plane/docs/spec.md](control-plane/docs/spec.md) |
| **Sidecar Proxy** | Java 21, Spring Boot | Data plane deployed as second container, handles traffic routing, retries, metrics | [sidecar-proxy-new/docs/spec.md](sidecar-proxy-new/docs/spec.md) |
| **API Gateway** | Go | System entry point, proxies requests through sidecar | [go-services/README.md](go-services/README.md) |
| **Inventory Service** | Go | Backend service with fault injection for canary testing | [go-services/README.md](go-services/README.md) |
| **Monitoring** | Prometheus, Grafana | Auto-provisioned dashboards for observability | [k8s-monitoring/README.md](k8s-monitoring/README.md) |

## 🚀 Quick Start

### Prerequisites
- macOS with OrbStack (Docker + Kubernetes)
- Java 21 + Maven
- kubectl

### 1. Build Docker Images
```bash
docker build -t api-gateway:latest ./go-services/api-gateway
docker build -t inventory-service:latest ./go-services/inventory-service
docker build -t mesh-sidecar:latest ./sidecar-proxy-new
docker build -t mesh-control-plane:latest ./control-plane
```

### 2. Deploy to Kubernetes
```bash
kubectl apply -f control-plane/k8s/
kubectl apply -f sidecar-proxy-new/k8s/
kubectl apply -f k8s-monitoring/
```

### 3. Access Services
```bash
kubectl -n mesh port-forward svc/api-gateway 9090:8080
curl http://localhost:9090/api/inventory
```

## 📚 Documentation

| Document | Description |
|---|---|
| [Deployment Guide](docs/deployment.md) | Complete deployment instructions |
| [Canary Test Scenarios](control-plane/docs/test-scenarios.md) | 10 test scenarios for canary releases |
| [Control Plane Spec](control-plane/docs/spec.md) | Technical specification |
| [Sidecar Proxy Spec](sidecar-proxy-new/docs/spec.md) | Technical specification |

## 🎓 Key Features

### Canary Release Workflow
1. **Start:** `POST /api/v1/canary/start` with `initialWeight=10%`
2. **Deploy:** Control Plane creates canary Deployment + Service
3. **Route Update:** ConfigStore updates routes: stable=90%, canary=10%
4. **Evaluation:** Every 30s, check error rate vs threshold (default 5%)
5. **Decision:**
   - **Success** → increase weight by 10%
   - **Failure** → rollback immediately
6. **Promote:** When weight=100%, update stable deployment

### Traffic Splitting
Sidecar uses weighted random algorithm:
```
destinations: [stable(90), canary(10)]
random = ThreadLocalRandom.current().nextInt(100)
// 0..89 → stable, 90..99 → canary
```

### Graceful Degradation
If Control Plane becomes unavailable, sidecars continue operating with cached configuration.

## 📊 Monitoring

Grafana dashboard (http://localhost:3000, admin/admin) includes:
- RPS by version (stable/canary)
- Error Rate %
- Latency p95/p50
- Retry Rate
- Canary Weight gauge

## 🛠️ Tech Stack

| Component | Technology |
|---|---|
| Control Plane | Java 21, Spring Boot 3.2, Kubernetes Java Client |
| Sidecar Proxy | Java 21, Spring Boot 3.2, Micrometer, Prometheus |
| Business Services | Go 1.22 |
| Kubernetes | K8s manifests, RBAC, ConfigMaps |
| Monitoring | Prometheus, Grafana (auto-provisioned) |

## 📁 Project Structure

```
MeshItUp/
├── control-plane/          # Java — mesh management
├── sidecar-proxy-new/      # Java — data plane proxy
├── go-services/            # Go — business services
├── k8s-monitoring/         # Prometheus + Grafana
├── docs/                   # Common documentation
└── README.md               # This file
```

## 🎓 Educational Goals

This project demonstrates:
- **Sidecar pattern** — infrastructure concerns separated from business logic
- **Control plane architecture** — centralized config with graceful degradation
- **Canary release automation** — metrics-driven deployment decisions
- **Weighted routing** — traffic splitting without client awareness
- **Observability** — metrics collection, aggregation, visualization

---

**Coursework:** BSU Faculty of Applied Mathematics  
**Subject:** Service Mesh: Capabilities and Implementation  
**Year:** 2025
