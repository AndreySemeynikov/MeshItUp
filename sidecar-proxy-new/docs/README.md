# Sidecar Proxy — Data Plane for an Educational Service Mesh

A sidecar proxy built with Java 21 and Spring Boot 3.2 that forms the data plane of an educational Service Mesh. Deployed as a second container in every Kubernetes pod alongside the business service. A single Docker image is used for all pods — behavior is differentiated entirely through environment variables.

Part of a coursework project on "Service Mesh: Capabilities and Implementation" (BSU, Faculty of Applied Mathematics).

## Purpose

In a service mesh, the sidecar proxy is responsible for all network concerns so that business services don't have to be. This proxy intercepts outbound HTTP requests from its companion service, routes them to the correct destination (including weighted traffic splitting between stable and canary versions), collects per-request metrics, and retries failed calls — all transparently to the application.

## How It Works

The business service sends all outbound HTTP requests to `localhost:15001` (the sidecar) instead of calling other services directly. The sidecar then:

1. Looks up the matching route from the configuration received from the control plane
2. Selects a destination using weighted random (e.g. 90% stable, 10% canary)
3. Forwards the request to the selected destination
4. Retries on failure according to the retry policy
5. Records metrics (Prometheus + aggregated reports to control plane)
6. Returns the response back to the business service

```
Business Service → localhost:15001 (Sidecar)
                        │
                   ┌────┴────┐
                   │  Router  │ ← config from Control Plane (polled every N sec)
                   └────┬────┘
                        │ weighted random
                   ┌────┴──────────┐
                   ▼               ▼
              stable (90%)    canary (10%)
```

The sidecar has no knowledge of business logic. It only sees HTTP method, path, headers, and status codes.

## Architecture — Internal Modules

The proxy consists of four main components:

**ConfigSyncService** periodically polls the control plane (`GET /api/v1/config?serviceId=...`) and caches the configuration locally. If the control plane becomes unavailable, the sidecar continues operating with the last known config. The first sync happens eagerly at startup.

**Router** is a stateless component that matches the incoming request path against route definitions using Spring's `AntPathMatcher`, then selects a destination via weighted random. For example, with weights `[stable: 90, canary: 10]`, roughly 90% of requests go to the stable deployment and 10% to the canary.

**HttpForwarder** builds the target URL, copies headers (adding mesh-specific ones like `X-Request-Id`, `X-Mesh-Source`, `X-Mesh-Route-Version`), forwards the request, and implements retry logic. Retries are triggered on connection errors and configurable status codes (502, 503, 504 by default).

**MetricsCollector** serves two purposes. It exposes Prometheus metrics via Micrometer on a separate port (counters for total requests, errors, retries, and a timer for latency). It also periodically sends aggregated metric reports to the control plane, which uses them to evaluate canary health and decide whether to promote or roll back.

## Configuration

All configuration is provided through environment variables — there are no config files to mount.

| Variable | Required | Default | Description |
|---|---|---|---|
| `MESH_SERVICE_ID` | Yes | — | ID of the companion service (e.g. `api-gateway`) |
| `MESH_CONTROL_PLANE_URL` | Yes | — | Control plane URL (e.g. `http://mesh-control-plane.mesh.svc.cluster.local:8080`) |
| `PROXY_PORT` | No | `15001` | Port for proxying business traffic |
| `METRICS_PORT` | No | `15002` | Port for Prometheus `/actuator/prometheus` endpoint |
| `CONFIG_REFRESH_INTERVAL` | No | `10` | Config polling interval in seconds |
| `METRICS_REPORT_INTERVAL` | No | `30` | Metrics report interval in seconds |

## Tech Stack

| Component | Technology |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.2 (Spring MVC) |
| HTTP Client | RestClient with `SimpleClientHttpRequestFactory` |
| Metrics | Micrometer + Prometheus registry |
| Path Matching | `AntPathMatcher` (Spring) |
| Build | Maven |
| Container | Docker (eclipse-temurin:21-jre-alpine) |
| JVM | `-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport` |

Spring MVC was chosen over WebFlux for simplicity — a synchronous model is easier to debug and the load in this educational project is low. RestClient was chosen over Feign because the sidecar proxies arbitrary requests with arbitrary paths, not fixed API interfaces.

## Project Structure

```
sidecar-proxy/
├── src/main/java/com/mesh/sidecar/
│   ├── SidecarApplication.java              # Spring Boot entry point
│   ├── config/
│   │   ├── MeshProperties.java              # ENV-backed configuration
│   │   └── AppConfig.java                   # RestClient bean
│   ├── sync/
│   │   └── ConfigSyncService.java           # Config polling + caching
│   ├── routing/
│   │   └── Router.java                      # Path matching + weighted random
│   ├── forwarding/
│   │   └── HttpForwarder.java               # Request forwarding + retries
│   ├── metrics/
│   │   └── MetricsCollector.java            # Prometheus + control plane reports
│   ├── proxy/
│   │   └── ProxyController.java             # Single REST controller, orchestrates all modules
│   └── model/
│       ├── MeshConfig.java                  # Full config from control plane
│       ├── RouteDefinition.java             # Path pattern → destinations
│       ├── Destination.java                 # host:port + version + weight
│       ├── RetryPolicy.java                 # maxAttempts, delay, retriable codes
│       ├── ForwardResult.java               # Result of a forwarded request
│       └── MetricsReport.java               # Aggregated metrics for control plane
├── src/main/resources/
│   └── application.yml                      # Spring config (reads from ENV)
├── src/test/java/com/mesh/sidecar/
│   ├── routing/RouterTest.java              # Routing and weight distribution tests
│   ├── sync/ConfigSyncServiceTest.java      # Config sync tests
│   └── proxy/ProxyControllerIntegrationTest.java
├── pom.xml
└── Dockerfile
```

## Build and Run

### Build

```bash
mvn clean package -DskipTests
docker build -t mesh-sidecar:latest .
```

### Deploy in Kubernetes

The sidecar is not deployed independently — it runs as a second container inside each business service pod. Example from the API Gateway deployment:

```yaml
containers:
  - name: api-gateway
    image: api-gateway:latest
    ports:
      - containerPort: 8080
  - name: mesh-sidecar
    image: mesh-sidecar:latest
    ports:
      - containerPort: 15001
      - containerPort: 15002
    env:
      - name: MESH_SERVICE_ID
        value: "api-gateway"
      - name: MESH_CONTROL_PLANE_URL
        value: "http://mesh-control-plane.mesh.svc.cluster.local:8080"
```

### Run Tests

```bash
mvn test
```

Tests cover routing logic (path matching, weighted distribution with statistical validation, edge cases) and config synchronization.

## Exposed Endpoints

| Port | Path | Description |
|---|---|---|
| 15001 | `/**` | Proxy — accepts any HTTP request from the business service |
| 15002 | `/actuator/prometheus` | Prometheus metrics scrape endpoint |
| 15002 | `/actuator/health` | Health check |

## Prometheus Metrics

| Metric | Type | Labels | Description |
|---|---|---|---|
| `mesh_proxy_requests_total` | Counter | destination, version, status | Total proxied requests |
| `mesh_proxy_errors_total` | Counter | destination, version | Requests with status ≥ 500 |
| `mesh_proxy_retries_total` | Counter | destination, version | Total retry attempts |
| `mesh_proxy_request_duration_ms` | Timer | destination, version | Request latency |
