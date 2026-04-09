# Prometheus + Grafana — Мониторинг Service Mesh

Sidecar-прокси экспортируют метрики через Micrometer на порту 15002 по пути `/actuator/prometheus`. Prometheus скрейпит их каждые 15 секунд. Grafana визуализирует. Дашборд провизионируется автоматически при старте Grafana.

Метрики от каждого sidecar: `mesh_proxy_requests_total` (counter, tags: destination/version/status), `mesh_proxy_request_duration_ms` (timer), `mesh_proxy_errors_total` (counter), `mesh_proxy_retries_total` (counter).

---

## Этап 1: Prometheus

### 1.1 ConfigMap

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: prometheus-config
  namespace: mesh
data:
  prometheus.yml: |
    global:
      scrape_interval: 15s
      evaluation_interval: 15s
    scrape_configs:
      - job_name: 'mesh-sidecar'
        kubernetes_sd_configs:
          - role: pod
            namespaces:
              names: [mesh]
        relabel_configs:
          - source_labels: [__meta_kubernetes_pod_label_mesh]
            action: keep
            regex: "true"
          - source_labels: [__meta_kubernetes_pod_container_port_number]
            action: keep
            regex: "15002"
          - source_labels: [__meta_kubernetes_pod_name]
            target_label: pod
          - source_labels: [__meta_kubernetes_pod_label_app]
            target_label: app
          - source_labels: [__meta_kubernetes_pod_label_version]
            target_label: deploy_version
        metrics_path: /actuator/prometheus
      - job_name: 'prometheus'
        static_configs:
          - targets: ['localhost:9090']
EOF
```

### 1.2 RBAC

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ServiceAccount
metadata:
  name: prometheus
  namespace: mesh
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRole
metadata:
  name: prometheus
rules:
  - apiGroups: [""]
    resources: ["pods", "nodes", "services", "endpoints"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: ClusterRoleBinding
metadata:
  name: prometheus
subjects:
  - kind: ServiceAccount
    name: prometheus
    namespace: mesh
roleRef:
  kind: ClusterRole
  name: prometheus
  apiGroup: rbac.authorization.k8s.io
EOF
```

### 1.3 Deployment + Service

```bash
kubectl apply -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: prometheus
  namespace: mesh
spec:
  replicas: 1
  selector:
    matchLabels:
      app: prometheus
  template:
    metadata:
      labels:
        app: prometheus
    spec:
      serviceAccountName: prometheus
      containers:
        - name: prometheus
          image: prom/prometheus:v2.51.0
          args: ["--config.file=/etc/prometheus/prometheus.yml", "--storage.tsdb.path=/prometheus", "--storage.tsdb.retention.time=7d", "--web.enable-lifecycle"]
          ports: [{containerPort: 9090}]
          volumeMounts:
            - {name: config, mountPath: /etc/prometheus}
            - {name: storage, mountPath: /prometheus}
          resources:
            requests: {cpu: "100m", memory: "128Mi"}
            limits: {cpu: "500m", memory: "512Mi"}
      volumes:
        - {name: config, configMap: {name: prometheus-config}}
        - {name: storage, emptyDir: {}}
---
apiVersion: v1
kind: Service
metadata:
  name: prometheus
  namespace: mesh
spec:
  selector:
    app: prometheus
  ports: [{name: http, port: 9090, targetPort: 9090}]
  type: ClusterIP
EOF
```

### 1.4 Проверка

```bash
kubectl -n mesh rollout status deployment/prometheus
kubectl -n mesh port-forward svc/prometheus 9090:9090 &
# http://localhost:9090 -> Status -> Targets -> mesh-sidecar targets
curl 'http://localhost:9090/api/v1/query?query=mesh_proxy_requests_total' | jq .
```

---

## Этап 2: Grafana

### 2.1 Datasource + Dashboard provider

```bash
kubectl apply -f - <<'EOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-datasources
  namespace: mesh
data:
  datasources.yaml: |
    apiVersion: 1
    datasources:
      - name: Prometheus
        type: prometheus
        access: proxy
        url: http://prometheus.mesh.svc.cluster.local:9090
        isDefault: true
        editable: true
---
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboard-provider
  namespace: mesh
data:
  dashboards.yaml: |
    apiVersion: 1
    providers:
      - name: mesh
        orgId: 1
        folder: Service Mesh
        type: file
        disableDeletion: false
        editable: true
        options:
          path: /var/lib/grafana/dashboards
EOF
```

### 2.2 Дашборд (9 панелей, провизионируется автоматически)

```bash
kubectl apply -f - <<'DEOF'
apiVersion: v1
kind: ConfigMap
metadata:
  name: grafana-dashboards
  namespace: mesh
data:
  mesh-dashboard.json: |
    {"annotations":{"list":[]},"editable":true,"graphTooltip":1,"panels":[{"title":"RPS по версиям","type":"timeseries","gridPos":{"h":8,"w":12,"x":0,"y":0},"fieldConfig":{"defaults":{"unit":"reqps","custom":{"lineWidth":2,"fillOpacity":10}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_requests_total[1m])) by (version)","legendFormat":"{{version}}","refId":"A"}]},{"title":"Error Rate по версиям (%)","type":"timeseries","gridPos":{"h":8,"w":12,"x":12,"y":0},"fieldConfig":{"defaults":{"unit":"percent","min":0,"max":100,"custom":{"lineWidth":2,"fillOpacity":10},"thresholds":{"mode":"absolute","steps":[{"color":"green","value":null},{"color":"red","value":5}]}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_errors_total[1m])) by (version) / sum(rate(mesh_proxy_requests_total[1m])) by (version) * 100","legendFormat":"{{version}}","refId":"A"}]},{"title":"Latency p95 / p50 (ms)","type":"timeseries","gridPos":{"h":8,"w":12,"x":0,"y":8},"fieldConfig":{"defaults":{"unit":"ms","custom":{"lineWidth":2,"fillOpacity":10}},"overrides":[]},"targets":[{"expr":"histogram_quantile(0.95, sum(rate(mesh_proxy_request_duration_ms_bucket[1m])) by (le, version))","legendFormat":"p95 {{version}}","refId":"A"},{"expr":"histogram_quantile(0.50, sum(rate(mesh_proxy_request_duration_ms_bucket[1m])) by (le, version))","legendFormat":"p50 {{version}}","refId":"B"}]},{"title":"Retry Rate","type":"timeseries","gridPos":{"h":8,"w":12,"x":12,"y":8},"fieldConfig":{"defaults":{"unit":"ops","custom":{"lineWidth":2,"fillOpacity":10}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_retries_total[1m])) by (version)","legendFormat":"retries {{version}}","refId":"A"}]},{"title":"RPS по destination","type":"timeseries","gridPos":{"h":8,"w":12,"x":0,"y":16},"fieldConfig":{"defaults":{"unit":"reqps","custom":{"lineWidth":2,"fillOpacity":10}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_requests_total[1m])) by (destination, version)","legendFormat":"{{destination}} [{{version}}]","refId":"A"}]},{"title":"HTTP Status Codes","type":"timeseries","gridPos":{"h":8,"w":12,"x":12,"y":16},"fieldConfig":{"defaults":{"unit":"ops","custom":{"lineWidth":2,"fillOpacity":10}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_requests_total[1m])) by (status)","legendFormat":"HTTP {{status}}","refId":"A"}]},{"title":"Общий RPS","type":"stat","gridPos":{"h":4,"w":4,"x":0,"y":24},"fieldConfig":{"defaults":{"unit":"reqps","thresholds":{"steps":[{"color":"green","value":null}]}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_requests_total[1m]))","refId":"A"}]},{"title":"Error Rate","type":"stat","gridPos":{"h":4,"w":4,"x":4,"y":24},"fieldConfig":{"defaults":{"unit":"percent","thresholds":{"steps":[{"color":"green","value":null},{"color":"yellow","value":2},{"color":"red","value":5}]}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_errors_total[1m])) / sum(rate(mesh_proxy_requests_total[1m])) * 100","refId":"A"}]},{"title":"Canary Weight","type":"gauge","gridPos":{"h":4,"w":4,"x":8,"y":24},"fieldConfig":{"defaults":{"unit":"percent","min":0,"max":100,"thresholds":{"steps":[{"color":"blue","value":null},{"color":"green","value":50},{"color":"yellow","value":90}]}},"overrides":[]},"targets":[{"expr":"sum(rate(mesh_proxy_requests_total{version=\"canary\"}[1m])) / sum(rate(mesh_proxy_requests_total[1m])) * 100","refId":"A"}]}],"refresh":"5s","schemaVersion":39,"tags":["mesh","canary"],"time":{"from":"now-15m","to":"now"},"title":"Service Mesh Overview","uid":"mesh-overview"}
DEOF
```

### 2.3 Deployment + Service Grafana

```bash
kubectl apply -f - <<'EOF'
apiVersion: apps/v1
kind: Deployment
metadata:
  name: grafana
  namespace: mesh
spec:
  replicas: 1
  selector:
    matchLabels:
      app: grafana
  template:
    metadata:
      labels:
        app: grafana
    spec:
      containers:
        - name: grafana
          image: grafana/grafana:10.4.1
          ports: [{containerPort: 3000}]
          env:
            - {name: GF_SECURITY_ADMIN_USER, value: "admin"}
            - {name: GF_SECURITY_ADMIN_PASSWORD, value: "admin"}
            - {name: GF_AUTH_ANONYMOUS_ENABLED, value: "true"}
            - {name: GF_AUTH_ANONYMOUS_ORG_ROLE, value: "Viewer"}
          volumeMounts:
            - {name: datasources, mountPath: /etc/grafana/provisioning/datasources}
            - {name: dashboard-provider, mountPath: /etc/grafana/provisioning/dashboards}
            - {name: dashboards, mountPath: /var/lib/grafana/dashboards}
            - {name: storage, mountPath: /var/lib/grafana}
          resources:
            requests: {cpu: "100m", memory: "128Mi"}
            limits: {cpu: "500m", memory: "256Mi"}
      volumes:
        - {name: datasources, configMap: {name: grafana-datasources}}
        - {name: dashboard-provider, configMap: {name: grafana-dashboard-provider}}
        - {name: dashboards, configMap: {name: grafana-dashboards}}
        - {name: storage, emptyDir: {}}
---
apiVersion: v1
kind: Service
metadata:
  name: grafana
  namespace: mesh
spec:
  selector:
    app: grafana
  ports: [{name: http, port: 3000, targetPort: 3000}]
  type: ClusterIP
EOF
```

### 2.4 Проверка

```bash
kubectl -n mesh rollout status deployment/grafana
kubectl -n mesh port-forward svc/grafana 3000:3000 &
# http://localhost:3000 — admin/admin
# Dashboards -> Service Mesh -> Service Mesh Overview
```

---

## Этап 3: Генерация трафика

```bash
kubectl -n mesh port-forward svc/api-gateway 9090:8080 &

for i in $(seq 1 600); do
  curl -s -o /dev/null http://localhost:9090/api/inventory
  sleep 0.5
done
```

На дашборде: RPS `stable` ~2 req/s, Error Rate 0%, latency в мс, HTTP 200.

---

## Этап 4: Canary на живом дашборде

### Фоновая нагрузка

```bash
while true; do curl -s -o /dev/null http://localhost:9090/api/inventory; sleep 0.3; done
```

### Успешный canary

```bash
kubectl -n mesh port-forward svc/mesh-control-plane 8081:8080 &

curl -X POST http://localhost:8081/api/v1/canary/start \
  -H "Content-Type: application/json" \
  -d '{"serviceId":"inventory-service","canaryImage":"inventory-service:latest","canaryEnv":{"VERSION":"v2","FAULT_RATE":"0","PORT":"8080"},"initialWeight":10,"weightStep":10,"errorThreshold":5.0}'
```

На дашборде: появится линия `canary` в RPS, gauge покажет 10% -> 20% -> ... -> promote. Error Rate обеих версий ~0%.

### Сбойный canary

```bash
curl -X POST http://localhost:8081/api/v1/canary/start \
  -H "Content-Type: application/json" \
  -d '{"serviceId":"inventory-service","canaryImage":"inventory-service:latest","canaryEnv":{"VERSION":"v2-faulty","FAULT_RATE":"30","PORT":"8080"},"initialWeight":10,"weightStep":10,"errorThreshold":5.0}'
```

На дашборде: Error Rate `canary` подскочит до ~30%, появятся HTTP 500, через 30-60с rollback, canary исчезнет.

---

## Полезные PromQL

```promql
sum(rate(mesh_proxy_requests_total[1m]))                                                         # Общий RPS
sum(rate(mesh_proxy_errors_total{version="canary"}[1m])) / sum(rate(mesh_proxy_requests_total{version="canary"}[1m])) * 100  # Error rate canary %
sum(rate(mesh_proxy_requests_total{version="canary"}[1m])) / sum(rate(mesh_proxy_requests_total[1m])) * 100                  # Canary weight %
histogram_quantile(0.95, sum(rate(mesh_proxy_request_duration_ms_bucket[1m])) by (le, version))   # Latency p95
topk(5, sum(rate(mesh_proxy_errors_total[1m])) by (destination, version))                         # Top ошибок
```

---

## Все port-forward'ы

```bash
kubectl -n mesh port-forward svc/api-gateway 9090:8080 &
kubectl -n mesh port-forward svc/mesh-control-plane 8081:8080 &
kubectl -n mesh port-forward svc/prometheus 9091:9090 &
kubectl -n mesh port-forward svc/grafana 3000:3000 &
```

---

## Отладка

- **Prometheus не видит targets**: проверь `kubectl -n mesh get pods --show-labels | grep mesh=true` и что sidecar слушает 15002.
- **Grafana пустая**: проверь datasource URL (`http://prometheus.mesh.svc.cluster.local:9090`), генерируй трафик.
- **Имена метрик отличаются**: Micrometer может генерировать `mesh.proxy.requests` вместо `mesh_proxy_requests_total`. Посмотри реальные имена в Prometheus autocomplete (`mesh_`) и обнови PromQL в дашборде.

Ресурсы мониторинга: Prometheus ~512Mi + Grafana ~256Mi = ~768Mi RAM.
