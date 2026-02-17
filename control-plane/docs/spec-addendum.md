# Control Plane Spec — Дополнение (errata)

Данный документ содержит уточнения и исправления к `control-plane-spec.md`. Должен читаться вместе с основной спецификацией.

---

## 1. Требование к stable Deployment и Service: label `version: stable`

### Проблема

Когда control plane создаёт canary Deployment с labels `app: inventory-service, version: canary`, основной Kubernetes Service `inventory-service` с selector `app: inventory-service` (без version) начнёт направлять трафик и на canary поды тоже. Это ломает всю логику weighted routing — трафик будет попадать на canary в обход sidecar.

### Решение

Все stable Deployments и Services в mesh **обязаны** иметь label `version: stable` и selector по нему. Это требование к K8s манифестам бизнес-сервисов, не к control plane.

### Исправленный манифест Deployment (inventory-service)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-service
  namespace: mesh
  labels:
    app: inventory-service
    version: stable              # ← обязательно
    mesh: "true"
spec:
  replicas: 2
  selector:
    matchLabels:
      app: inventory-service
      version: stable            # ← обязательно в selector
  template:
    metadata:
      labels:
        app: inventory-service
        version: stable          # ← обязательно на подах
        mesh: "true"
    spec:
      containers:
        - name: inventory-service
          image: inventory-service:v1
          ports:
            - containerPort: 8080
          env:
            - name: VERSION
              value: "v1"
            - name: FAULT_RATE
              value: "0"
            - name: PORT
              value: "8080"
        - name: mesh-sidecar
          image: mesh-sidecar:latest
          ports:
            - containerPort: 15001
            - containerPort: 15002
          env:
            - name: MESH_SERVICE_ID
              value: "inventory-service"
            - name: MESH_CONTROL_PLANE_URL
              value: "http://mesh-control-plane.mesh.svc.cluster.local:8080"
          resources:
            requests:
              cpu: "50m"
              memory: "64Mi"
            limits:
              cpu: "200m"
              memory: "128Mi"
```

### Исправленный манифест Service (inventory-service)

```yaml
apiVersion: v1
kind: Service
metadata:
  name: inventory-service
  namespace: mesh
  labels:
    app: inventory-service
    version: stable
spec:
  selector:
    app: inventory-service
    version: stable              # ← только stable поды
  ports:
    - name: http
      port: 8080
      targetPort: 8080
  type: ClusterIP
```

### Результат

```
Service: inventory-service         → selector: app=inventory-service, version=stable → Pod 1, Pod 2
Service: inventory-service-canary  → selector: app=inventory-service, version=canary → Pod 3

Трафик разделён на уровне K8s Services.
Sidecar у api-gateway направляет запросы на конкретный Service DNS по весам из конфигурации.
```

Это же требование распространяется на **все** сервисы в mesh (включая api-gateway), даже если canary на них пока не планируется — для единообразия и готовности к будущим canary.

---

## 2. MESH_SERVICE_ID в canary поде

### Проблема

В основной спецификации KubernetesDeployer при создании canary Deployment не уточнено, какой `MESH_SERVICE_ID` задаётся sidecar-контейнеру в canary поде.

### Решение

Sidecar в canary поде должен получать `MESH_SERVICE_ID=inventory-service` — **тот же ID что и у stable**, а не `inventory-service-canary`.

Причина: с точки зрения mesh это один и тот же сервис, просто другая версия. Sidecar в canary поде запрашивает конфигурацию у control plane с `serviceId=inventory-service` и получает маршруты для inventory-service. Если бы ID был `inventory-service-canary`, control plane не нашёл бы для него маршрутов.

### Исправление в KubernetesDeployer.createCanaryDeployment

Sidecar-контейнер в canary Deployment:

```java
V1Container sidecarContainer = new V1Container()
    .name("mesh-sidecar")
    .image("mesh-sidecar:latest")
    .addPortsItem(new V1ContainerPort().containerPort(15001))
    .addPortsItem(new V1ContainerPort().containerPort(15002))
    .addEnvItem(new V1EnvVar()
        .name("MESH_SERVICE_ID")
        .value(serviceId))              // "inventory-service", НЕ "inventory-service-canary"
    .addEnvItem(new V1EnvVar()
        .name("MESH_CONTROL_PLANE_URL")
        .value("http://mesh-control-plane.mesh.svc.cluster.local:8080"));
```

---

## 3. Дополнение к mesh-config.yaml в документации

В основной спецификации `mesh-config.yaml` описывает сервисы и маршруты. Нужно уточнить, что `host` в destinations маршрутов ссылается на **K8s Service DNS-имя**, а не на имя пода или Deployment. Это важно для понимания как canary destination добавляется динамически.

### Stable состояние (из файла)

```yaml
routes:
  - source: api-gateway
    pathPattern: /api/inventory/**
    destinations:
      - serviceId: inventory-service
        host: inventory-service.mesh.svc.cluster.local    # ← K8s Service DNS
        port: 8080
        version: stable
        weight: 100
```

### После запуска canary (in-memory, добавлено control plane)

```yaml
routes:
  - source: api-gateway
    pathPattern: /api/inventory/**
    destinations:
      - serviceId: inventory-service
        host: inventory-service.mesh.svc.cluster.local          # ← stable Service
        port: 8080
        version: stable
        weight: 90
      - serviceId: inventory-service
        host: inventory-service-canary.mesh.svc.cluster.local   # ← canary Service (создан control plane)
        port: 8080
        version: canary
        weight: 10
```

Sidecar у api-gateway делает HTTP-запрос на конкретный DNS-адрес (`inventory-service.mesh...` или `inventory-service-canary.mesh...`), и Kubernetes DNS резолвит его в IP нужного Service, который в свою очередь направляет на поды с matching selector.
