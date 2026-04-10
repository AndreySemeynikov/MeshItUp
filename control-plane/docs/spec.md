# Control Plane — Техническое задание и спецификация реализации

## Контекст проекта

Control Plane — управляющий компонент учебного Service Mesh. Это единственный источник правды для всей mesh: хранит конфигурацию сервисов и маршрутов, раздаёт её sidecar-прокси, принимает метрики и управляет полным жизненным циклом canary-релизов.

### Место в архитектуре

```
mesh-config.yaml (ConfigMap)
        │
        ▼ при старте + POST /reload
┌──────────────────────────────────────────────┐
│              Control Plane                    │
│                                              │
│  ┌─────────────┐  ┌──────────────────────┐   │
│  │ Config Store │  │  Canary Controller   │   │
│  │ (in-memory)  │  │  (K8s Java Client)   │   │
│  └──────┬───────┘  └──────────┬───────────┘   │
│         │                     │               │
│  ┌──────▼───────┐  ┌─────────▼────────────┐  │
│  │ Config API   │  │  Metrics Aggregator   │  │
│  │ GET /config  │  │  POST /metrics/report │  │
│  └──────────────┘  └──────────────────────┘   │
└──────────────────────────────────────────────┘
        │                       ▲
        │ GET /config           │ POST /metrics/report
        ▼                       │
┌───────────────┐      ┌───────────────┐
│ Sidecar Proxy │      │ Sidecar Proxy │
│ (api-gateway) │      │ (api-gateway) │
└───────────────┘      └───────────────┘
```

### Связанные компоненты

- **Sidecar Proxy** (Java/Spring Boot) — data plane, запрашивает конфигурацию у control plane каждые N секунд, отправляет метрики
- **API Gateway** (Go) — бизнес-сервис, точка входа
- **Inventory Service** (Go) — конечный сервис, на нём проводится canary release
- **Prometheus** — скрейпит метрики с sidecar-прокси для Grafana
- **Grafana** — визуализация дашбордов

Control plane **не участвует** в обработке пользовательского трафика. Если он упадёт — sidecar-прокси продолжают работать по кэшированной конфигурации.

---

## Технологический стек

| Компонент | Технология | Версия |
|---|---|---|
| Язык | Java | 21 |
| Фреймворк | Spring Boot | 3.2+ |
| HTTP | Spring MVC (RestController) | — |
| K8s интеграция | Kubernetes Java Client (official) | 21.x |
| HTTP-клиент | RestClient (Spring Boot 3.2) | — |
| YAML-парсинг | Jackson Dataformat YAML | — |
| Документация API | SpringDoc OpenAPI (Swagger UI) | 2.x |
| Сборка | Maven | — |
| Контейнеризация | Docker (eclipse-temurin:21-jre) | — |

---

## Конфигурация через переменные окружения

| Переменная | Обязательная | Default | Описание |
|---|---|---|---|
| `MESH_CONFIG_PATH` | Нет | `/config/mesh-config.yaml` | Путь к файлу базовой конфигурации (монтируется из ConfigMap) |
| `SERVER_PORT` | Нет | `8080` | Порт HTTP-сервера control plane |
| `CANARY_EVALUATION_INTERVAL` | Нет | `30` | Интервал в секундах между оценками canary |
| `CANARY_DEFAULT_INITIAL_WEIGHT` | Нет | `10` | Начальный процент трафика на canary |
| `CANARY_DEFAULT_WEIGHT_STEP` | Нет | `10` | Шаг увеличения веса canary при успешной оценке |
| `CANARY_DEFAULT_ERROR_THRESHOLD` | Нет | `5.0` | Порог error rate (%) для rollback |
| `CANARY_SUCCESS_COUNT_TO_PROMOTE` | Нет | `3` | Сколько успешных оценок подряд нужно для promote на следующий шаг |
| `K8S_NAMESPACE` | Нет | `mesh` | Namespace в котором control plane создаёт canary Deployments |

В `application.yml`:

```yaml
spring:
  application:
    name: mesh-control-plane

server:
  port: ${SERVER_PORT:8080}

mesh:
  config-path: ${MESH_CONFIG_PATH:/config/mesh-config.yaml}

canary:
  evaluation-interval: ${CANARY_EVALUATION_INTERVAL:30}
  default-initial-weight: ${CANARY_DEFAULT_INITIAL_WEIGHT:10}
  default-weight-step: ${CANARY_DEFAULT_WEIGHT_STEP:10}
  default-error-threshold: ${CANARY_DEFAULT_ERROR_THRESHOLD:5.0}
  success-count-to-promote: ${CANARY_SUCCESS_COUNT_TO_PROMOTE:3}

k8s:
  namespace: ${K8S_NAMESPACE:mesh}

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

Маппятся в два `@ConfigurationProperties`-класса: `MeshProperties` и `CanaryProperties`.

---

## Базовая конфигурация: mesh-config.yaml

Этот файл — декларативное описание mesh. Монтируется в pod через Kubernetes ConfigMap. Control plane читает его при старте и по вызову `POST /api/v1/config/reload`.

```yaml
services:
  - id: api-gateway
    host: api-gateway.mesh.svc.cluster.local
    port: 8080
    healthPath: /health

  - id: inventory-service
    host: inventory-service.mesh.svc.cluster.local
    port: 8080
    healthPath: /health

routes:
  - source: api-gateway
    pathPattern: /api/inventory/**
    destinations:
      - serviceId: inventory-service
        host: inventory-service.mesh.svc.cluster.local
        port: 8080
        version: stable
        weight: 100

retryPolicy:
  maxAttempts: 3
  delayMs: 500
  retriableStatusCodes:
    - 502
    - 503
    - 504
```

**Правила:**
- Каждый сервис имеет уникальный `id`.
- `routes[].source` ссылается на `id` сервиса-отправителя. По этому полю control plane фильтрует маршруты при ответе на `GET /api/v1/config?serviceId=...`.
- `destinations[].weight` — в сумме по одному route должны давать 100.
- `retryPolicy` — глобальная, одна на весь mesh.

---

## Структура проекта

```
control-plane/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/
    │   ├── java/com/mesh/controlplane/
    │   │   ├── ControlPlaneApplication.java
    │   │   │
    │   │   ├── config/
    │   │   │   ├── MeshProperties.java            — @ConfigurationProperties для mesh.*
    │   │   │   ├── CanaryProperties.java           — @ConfigurationProperties для canary.*
    │   │   │   └── KubernetesClientConfig.java     — бин ApiClient для K8s Java Client
    │   │   │
    │   │   ├── model/
    │   │   │   ├── ServiceDefinition.java          — описание сервиса (id, host, port, healthPath)
    │   │   │   ├── RouteDefinition.java            — маршрут (source, pathPattern, destinations)
    │   │   │   ├── Destination.java                — destination (host, port, version, weight)
    │   │   │   ├── RetryPolicy.java                — политика retry
    │   │   │   ├── MeshConfig.java                 — полная конфигурация (services + routes + retry)
    │   │   │   ├── CanaryState.java                — состояние canary-релиза
    │   │   │   ├── CanaryStartRequest.java         — тело запроса POST /canary/start
    │   │   │   ├── MetricsReport.java              — отчёт от sidecar
    │   │   │   └── MetricsEntry.java               — одна запись в отчёте (destination, version, counts)
    │   │   │
    │   │   ├── store/
    │   │   │   ├── ConfigStore.java                — in-memory хранилище конфигурации
    │   │   │   └── MetricsStore.java               — in-memory хранилище метрик от sidecar
    │   │   │
    │   │   ├── loader/
    │   │   │   └── ConfigFileLoader.java           — чтение и парсинг mesh-config.yaml
    │   │   │
    │   │   ├── canary/
    │   │   │   ├── CanaryManager.java              — жизненный цикл canary (start/promote/rollback)
    │   │   │   ├── CanaryEvaluator.java            — scheduled оценка метрик canary
    │   │   │   └── KubernetesDeployer.java         — создание/удаление/патч Deployments и Services
    │   │   │
    │   │   └── api/
    │   │       ├── ConfigController.java           — GET /config, POST /config/reload
    │   │       ├── ServiceController.java          — GET /services
    │   │       ├── RouteController.java            — GET /routes
    │   │       ├── MetricsController.java          — POST /metrics/report
    │   │       └── CanaryController.java           — POST /canary/start, GET /canary/status, etc.
    │   │
    │   └── resources/
    │       └── application.yml
    │
    └── test/
        └── java/com/mesh/controlplane/
            ├── canary/
            │   ├── CanaryManagerTest.java
            │   └── CanaryEvaluatorTest.java
            └── routing/
                └── ConfigStoreTest.java
```

---

## Модели данных

### ServiceDefinition

```java
public record ServiceDefinition(
    String id,          // "inventory-service"
    String host,        // "inventory-service.mesh.svc.cluster.local"
    int port,           // 8080
    String healthPath   // "/health"
) {}
```

### RouteDefinition

```java
public record RouteDefinition(
    String source,                  // "api-gateway"
    String pathPattern,             // "/api/inventory/**"
    List<Destination> destinations  // список destinations с весами
) {}
```

### Destination

```java
public record Destination(
    String serviceId,   // "inventory-service" — ссылка на ServiceDefinition
    String host,        // "inventory-service.mesh.svc.cluster.local"
    int port,           // 8080
    String version,     // "stable" или "canary"
    int weight          // 0..100
) {}
```

### RetryPolicy

```java
public record RetryPolicy(
    int maxAttempts,                        // 3
    long delayMs,                           // 500
    List<Integer> retriableStatusCodes      // [502, 503, 504]
) {}
```

### MeshConfig — полная конфигурация

Это то, что хранится в ConfigStore и отдаётся sidecar-прокси.

```java
public record MeshConfig(
    int version,                        // инкрементируется при каждом изменении
    List<ServiceDefinition> services,
    List<RouteDefinition> routes,
    RetryPolicy retryPolicy
) {}
```

### CanaryStartRequest — тело POST /canary/start

```java
public record CanaryStartRequest(
    String serviceId,          // "inventory-service"
    String canaryImage,        // "inventory-service:v2"
    Map<String, String> canaryEnv,  // {"VERSION": "v2", "FAULT_RATE": "0"}
    Integer initialWeight,     // 10 (nullable, default из properties)
    Integer weightStep,        // 10 (nullable, default из properties)
    Double errorThreshold      // 5.0 (nullable, default из properties)
) {}
```

### CanaryState — состояние canary-релиза

```java
public class CanaryState {
    public enum Status { IDLE, IN_PROGRESS, PROMOTED, ROLLED_BACK }

    private String serviceId;               // "inventory-service"
    private Status status;                  // IN_PROGRESS
    private String stableVersion;           // "v1"
    private String canaryVersion;           // "v2"
    private String canaryImage;             // "inventory-service:v2"
    private int currentWeight;              // 10
    private int weightStep;                 // 10
    private double errorThreshold;          // 5.0
    private int consecutiveSuccessCount;    // сколько оценок подряд были успешными
    private Instant startedAt;              // когда запущен
    private Instant lastEvaluationAt;       // когда последний раз оценивали
    private String lastEvaluationResult;    // "OK: canary error rate 1.2% <= threshold 5.0%"
    // getters, setters
}
```

### MetricsReport — отчёт от sidecar

```java
public record MetricsReport(
    String proxyId,             // "api-gateway-sidecar"
    String serviceId,           // "api-gateway"
    Instant timestamp,
    int windowSeconds,          // 30
    List<MetricsEntry> entries
) {}
```

### MetricsEntry — одна запись в отчёте

```java
public record MetricsEntry(
    String destination,     // "inventory-service"
    String version,         // "stable" или "canary"
    int requestCount,       // 87
    int errorCount,         // 1
    long avgLatencyMs       // 14
) {}
```

---

## Модуль: ConfigFileLoader

### Ответственность
Чтение и парсинг `mesh-config.yaml`.

### Поведение

1. Читает файл по пути из `MeshProperties.configPath`.
2. Парсит YAML через Jackson (`ObjectMapper` с `YAMLFactory`).
3. Валидирует: все serviceId в routes.destinations должны ссылаться на существующие services. Веса в каждом route должны суммироваться в 100.
4. Возвращает объект с parsed services, routes, retryPolicy.
5. Если файл не найден или невалиден — логирует ERROR и бросает исключение при старте. Control plane не должен запускаться с невалидной конфигурацией.

### Используется

- При старте приложения (`@PostConstruct` в ConfigStore или `ApplicationRunner`).
- При вызове `POST /api/v1/config/reload`.

Зависимость:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
</dependency>
```

---

## Модуль: ConfigStore

### Ответственность
In-memory хранилище конфигурации mesh. Единственный источник данных для API.

### Внутреннее устройство

```java
@Component
public class ConfigStore {
    private volatile MeshConfig currentConfig;
    private final AtomicInteger versionCounter = new AtomicInteger(0);

    // Загрузка базовой конфигурации из файла
    public void loadFromFile(List<ServiceDefinition> services,
                             List<RouteDefinition> routes,
                             RetryPolicy retryPolicy) { ... }

    // Получить полную конфигурацию
    public MeshConfig getFullConfig() { ... }

    // Получить конфигурацию для конкретного sidecar (фильтр по serviceId)
    public MeshConfig getConfigForService(String serviceId) { ... }

    // Обновить маршруты (используется canary controller)
    public void updateRouteDestinations(String serviceId, List<Destination> newDestinations) { ... }

    // Добавить destination в существующий маршрут
    public void addCanaryDestination(String serviceId, Destination canaryDestination, int stableWeight) { ... }

    // Убрать canary destination, вернуть stable=100%
    public void removeCanaryDestination(String serviceId) { ... }
}
```

### Метод getConfigForService

Фильтрует `routes` по `source == serviceId`. Возвращает `MeshConfig` только с маршрутами, релевантными данному sidecar. Сервисы и retryPolicy возвращаются полностью.

### Потокобезопасность

- `currentConfig` — `volatile`. Чтение из нескольких потоков (HTTP-запросы от sidecar) безопасно.
- Запись (обновление маршрутов) происходит из одного потока (canary controller или reload). Используется `synchronized` на методах записи.
- При каждом изменении `versionCounter` инкрементируется. Sidecar сравнивает version и обновляет кэш только при изменении.

---

## Модуль: MetricsStore

### Ответственность
Хранение метрик от sidecar-прокси для использования canary evaluator.

### Внутреннее устройство

```java
@Component
public class MetricsStore {
    // Ключ: "destination:version" (e.g. "inventory-service:canary")
    // Значение: агрегированные метрики за текущее окно
    private final ConcurrentHashMap<String, AggregatedMetrics> currentWindow = new ConcurrentHashMap<>();

    // Принять отчёт от sidecar
    public void ingest(MetricsReport report) { ... }

    // Получить агрегированные метрики для canary evaluator
    public Map<String, AggregatedMetrics> getAndReset() { ... }
}
```

### AggregatedMetrics

```java
public class AggregatedMetrics {
    private final AtomicInteger totalRequests = new AtomicInteger(0);
    private final AtomicInteger totalErrors = new AtomicInteger(0);
    private final AtomicLong totalLatency = new AtomicLong(0);

    public void add(int requests, int errors, long avgLatency) {
        totalRequests.addAndGet(requests);
        totalErrors.addAndGet(errors);
        totalLatency.addAndGet(avgLatency * requests); // weighted sum
    }

    public double getErrorRate() {
        int req = totalRequests.get();
        return req == 0 ? 0.0 : (double) totalErrors.get() / req * 100;
    }
}
```

### Метод ingest

Принимает `MetricsReport`, проходит по `entries`, для каждой (destination, version) вызывает `add()` на соответствующем `AggregatedMetrics`. Несколько sidecar могут отправлять отчёты одновременно — `AtomicInteger` обеспечивает потокобезопасность.

### Метод getAndReset

Возвращает snapshot текущих метрик и сбрасывает счётчики. Вызывается из `CanaryEvaluator` каждые `evaluationInterval` секунд. Атомарность обеспечивается через `ConcurrentHashMap.replaceAll` или создание нового `HashMap` и swap.

---

## Модуль: KubernetesDeployer

### Ответственность
Создание, обновление и удаление Kubernetes Deployments и Services для canary. Единственный модуль, который взаимодействует с Kubernetes API.

### Зависимость

```xml
<dependency>
    <groupId>io.kubernetes</groupId>
    <artifactId>client-java</artifactId>
    <version>21.0.1</version>
</dependency>
```

### Конфигурация K8s клиента

```java
@Configuration
public class KubernetesClientConfig {
    @Bean
    public ApiClient apiClient() throws IOException {
        // Внутри Pod — использует ServiceAccount token автоматически
        ApiClient client = ClientBuilder.cluster().build();
        Configuration.setDefaultApiClient(client);
        return client;
    }

    @Bean
    public AppsV1Api appsV1Api(ApiClient client) {
        return new AppsV1Api(client);
    }

    @Bean
    public CoreV1Api coreV1Api(ApiClient client) {
        return new CoreV1Api(client);
    }
}
```

`ClientBuilder.cluster()` автоматически использует ServiceAccount token, который Kubernetes монтирует в каждый pod. Для этого нужен ServiceAccount с правами на создание/удаление Deployments и Services (см. раздел RBAC ниже).

### Методы

**createCanaryDeployment(String serviceId, String canaryImage, Map<String, String> env)**

Создаёт Deployment:
- name: `{serviceId}-canary` (e.g. `inventory-service-canary`)
- namespace: из `K8S_NAMESPACE`
- replicas: 1
- labels: `app: {serviceId}`, `version: canary`, `mesh: "true"`
- containers:
  - Бизнес-контейнер: image = `canaryImage`, env из `canaryEnv`, порт 8080
  - Sidecar-контейнер: image = `mesh-sidecar:latest`, env: `MESH_SERVICE_ID={serviceId}-canary`, `MESH_CONTROL_PLANE_URL=...`

**createCanaryService(String serviceId)**

Создаёт Service:
- name: `{serviceId}-canary` (e.g. `inventory-service-canary`)
- selector: `app: {serviceId}`, `version: canary`
- port: 8080
- type: ClusterIP

Этот Service нужен, чтобы sidecar мог адресовать трафик конкретно на canary pod через DNS-имя `inventory-service-canary.mesh.svc.cluster.local`.

**patchStableDeployment(String serviceId, String newImage, Map<String, String> newEnv)**

Патчит основной Deployment (обновляет image и env бизнес-контейнера). Kubernetes выполнит rolling update автоматически. Используется при promote — когда canary прошёл проверку и нужно обновить stable поды.

**deleteCanaryDeployment(String serviceId)**

Удаляет Deployment `{serviceId}-canary`.

**deleteCanaryService(String serviceId)**

Удаляет Service `{serviceId}-canary`.

### Обработка ошибок

Все вызовы Kubernetes API оборачиваются в try-catch. При `ApiException`:
- Логируется ERROR с кодом и телом ответа.
- Если это `409 Conflict` (ресурс уже существует) — логируется WARN и продолжение.
- Если это `404 Not Found` (удаление несуществующего) — логируется WARN и продолжение.
- Иначе — пробрасывается исключение, canary переходит в состояние ошибки.

---

## Модуль: CanaryManager

### Ответственность
Управление жизненным циклом canary-релиза. Оркестрирует ConfigStore, KubernetesDeployer и CanaryEvaluator.

### Состояние

Хранит `CanaryState` — один на весь control plane. В данном прототипе поддерживается только один активный canary одновременно.

### Метод start(CanaryStartRequest request)

Последовательность действий:

1. Проверить что нет активного canary (`status == IDLE`). Если есть — вернуть 409 Conflict.
2. Проверить что `serviceId` существует в ConfigStore.
3. Создать `CanaryState` со статусом `IN_PROGRESS`.
4. Вызвать `KubernetesDeployer.createCanaryDeployment(...)`.
5. Вызвать `KubernetesDeployer.createCanaryService(...)`.
6. Подождать, пока canary pod станет Ready (опрос через K8s API, таймаут 60 секунд).
7. Обновить маршруты в ConfigStore: добавить canary destination с `initialWeight`, уменьшить stable weight.
8. Вернуть CanaryState.

Если на любом шаге произошла ошибка — откатить всё что было создано (удалить Deployment и Service), сбросить CanaryState в IDLE.

### Метод promote()

1. Патчить основной Deployment: `KubernetesDeployer.patchStableDeployment(serviceId, canaryImage, canaryEnv)`.
2. Подождать rolling update (опрос Ready-условий, таймаут 120 секунд).
3. Удалить canary Deployment и Service.
4. Сбросить маршруты: stable=100%, убрать canary destination.
5. Обновить CanaryState: status=PROMOTED.

### Метод rollback()

1. Удалить canary Deployment и Service.
2. Сбросить маршруты: stable=100%, убрать canary destination.
3. Обновить CanaryState: status=ROLLED_BACK.

### Метод increaseWeight()

Вызывается из CanaryEvaluator при успешной оценке.

1. `currentWeight += weightStep`.
2. Если `currentWeight >= 100` → вызвать `promote()`.
3. Иначе — обновить веса в ConfigStore: `canary=currentWeight`, `stable=100-currentWeight`.

---

## Модуль: CanaryEvaluator

### Ответственность
Периодическая оценка метрик canary и принятие решения: увеличить вес, откатить или ждать.

### Поведение

Запускается по `@Scheduled(fixedDelayString = "${canary.evaluation-interval}000")`.

Каждый тик:

1. Если `CanaryState.status != IN_PROGRESS` → пропустить.
2. Получить метрики из `MetricsStore.getAndReset()`.
3. Найти записи для canary version и stable version текущего canary serviceId.
4. Вычислить:
   - `canaryErrorRate = canaryErrors / canaryRequests * 100`
   - `stableErrorRate = stableErrors / stableRequests * 100`
5. Если `canaryRequests == 0` → недостаточно данных, пропустить (логировать WARN).
6. Если `canaryErrorRate > errorThreshold`:
   - Логировать: `"Canary error rate {canaryErrorRate}% exceeds threshold {errorThreshold}%. Rolling back."`
   - Вызвать `CanaryManager.rollback()`.
7. Если `canaryErrorRate <= errorThreshold`:
   - Инкрементировать `consecutiveSuccessCount`.
   - Если `consecutiveSuccessCount >= successCountToPromote`:
     - Вызвать `CanaryManager.increaseWeight()`.
     - Сбросить `consecutiveSuccessCount = 0`.
   - Иначе — ждать следующей оценки.
8. Обновить `lastEvaluationAt` и `lastEvaluationResult` в CanaryState.

### Зачем consecutiveSuccessCount

Одна удачная оценка может быть случайностью (мало запросов попало на canary). Требование нескольких успешных оценок подряд перед повышением веса делает решение надёжнее. Значение по умолчанию — 3 (то есть 3 × 30 секунд = 90 секунд стабильной работы перед каждым повышением).

---

## REST API — полная спецификация

### GET /api/v1/config

Конфигурация для sidecar-прокси.

**Query параметры:**
- `serviceId` (обязательный) — идентификатор сервиса, для которого запрашивается конфигурация.

**Ответ 200:**

```json
{
  "version": 5,
  "routes": [
    {
      "source": "api-gateway",
      "pathPattern": "/api/inventory/**",
      "destinations": [
        {
          "serviceId": "inventory-service",
          "host": "inventory-service.mesh.svc.cluster.local",
          "port": 8080,
          "version": "stable",
          "weight": 90
        },
        {
          "serviceId": "inventory-service",
          "host": "inventory-service-canary.mesh.svc.cluster.local",
          "port": 8080,
          "version": "canary",
          "weight": 10
        }
      ]
    }
  ],
  "retryPolicy": {
    "maxAttempts": 3,
    "delayMs": 500,
    "retriableStatusCodes": [502, 503, 504]
  }
}
```

Маршруты отфильтрованы: возвращаются только те, где `source == serviceId`. Сервисы не возвращаются (sidecar они не нужны). RetryPolicy возвращается всегда.

### POST /api/v1/config/reload

Перечитать `mesh-config.yaml` и обновить базовую конфигурацию.

**Тело:** пустое.

**Ответ 200:**

```json
{
  "status": "reloaded",
  "version": 6,
  "servicesCount": 2,
  "routesCount": 1
}
```

**Ответ 500** — если файл не найден или невалиден.

### GET /api/v1/services

Список зарегистрированных сервисов.

**Ответ 200:**

```json
[
  {
    "id": "api-gateway",
    "host": "api-gateway.mesh.svc.cluster.local",
    "port": 8080,
    "healthPath": "/health"
  },
  {
    "id": "inventory-service",
    "host": "inventory-service.mesh.svc.cluster.local",
    "port": 8080,
    "healthPath": "/health"
  }
]
```

### GET /api/v1/routes

Текущие маршруты с актуальными весами.

**Ответ 200:**

```json
[
  {
    "source": "api-gateway",
    "pathPattern": "/api/inventory/**",
    "destinations": [
      {"serviceId": "inventory-service", "host": "...", "port": 8080, "version": "stable", "weight": 90},
      {"serviceId": "inventory-service", "host": "...", "port": 8080, "version": "canary", "weight": 10}
    ]
  }
]
```

### POST /api/v1/metrics/report

Приём метрик от sidecar-прокси.

**Тело:**

```json
{
  "proxyId": "api-gateway-sidecar",
  "serviceId": "api-gateway",
  "timestamp": "2026-01-15T12:00:30Z",
  "windowSeconds": 30,
  "entries": [
    {
      "destination": "inventory-service",
      "version": "stable",
      "requestCount": 87,
      "errorCount": 1,
      "avgLatencyMs": 14
    },
    {
      "destination": "inventory-service",
      "version": "canary",
      "requestCount": 13,
      "errorCount": 4,
      "avgLatencyMs": 45
    }
  ]
}
```

**Ответ 200:**

```json
{"status": "accepted"}
```

### POST /api/v1/canary/start

Запуск canary-релиза.

**Тело:**

```json
{
  "serviceId": "inventory-service",
  "canaryImage": "inventory-service:v2",
  "canaryEnv": {
    "VERSION": "v2",
    "FAULT_RATE": "0"
  },
  "initialWeight": 10,
  "weightStep": 10,
  "errorThreshold": 5.0
}
```

Поля `initialWeight`, `weightStep`, `errorThreshold` — опциональные. Если не указаны, берутся из `CanaryProperties` (defaults).

**Ответ 200:**

```json
{
  "status": "IN_PROGRESS",
  "serviceId": "inventory-service",
  "canaryVersion": "v2",
  "currentWeight": 10,
  "startedAt": "2026-01-15T12:00:00Z"
}
```

**Ответ 409** — если уже есть активный canary.
**Ответ 404** — если serviceId не найден в ConfigStore.
**Ответ 500** — если не удалось создать Deployment/Service в K8s.

### GET /api/v1/canary/status

Текущее состояние canary.

**Ответ 200:**

```json
{
  "status": "IN_PROGRESS",
  "serviceId": "inventory-service",
  "stableVersion": "v1",
  "canaryVersion": "v2",
  "currentWeight": 30,
  "weightStep": 10,
  "errorThreshold": 5.0,
  "consecutiveSuccessCount": 2,
  "startedAt": "2026-01-15T12:00:00Z",
  "lastEvaluationAt": "2026-01-15T12:03:00Z",
  "lastEvaluationResult": "OK: canary error rate 1.2% <= threshold 5.0%"
}
```

Если canary не активен:

```json
{
  "status": "IDLE"
}
```

### POST /api/v1/canary/promote

Ручной promote — немедленно обновить stable на canary версию.

**Ответ 200:**

```json
{
  "status": "PROMOTED",
  "serviceId": "inventory-service",
  "newVersion": "v2"
}
```

**Ответ 409** — если canary не в статусе IN_PROGRESS.

### POST /api/v1/canary/rollback

Ручной rollback — немедленно удалить canary и вернуть stable=100%.

**Ответ 200:**

```json
{
  "status": "ROLLED_BACK",
  "serviceId": "inventory-service"
}
```

**Ответ 409** — если canary не в статусе IN_PROGRESS.

---

## Canary Release — полный flow

### Начальное состояние

```
Deployment: inventory-service (replicas: 2, image: inventory-service:v1)
  Pod 1: inventory v1 + sidecar
  Pod 2: inventory v1 + sidecar

Service: inventory-service → selector: app=inventory-service

Routes: api-gateway → /api/inventory/** → inventory-service:stable (100%)

CanaryState: IDLE
```

### POST /canary/start → Фаза 1

```
Deployment: inventory-service (replicas: 2, image: v1)         ← stable
Deployment: inventory-service-canary (replicas: 1, image: v2)  ← canary (создан control plane)

Service: inventory-service → selector: app=inventory-service
Service: inventory-service-canary → selector: app=inventory-service, version=canary

Routes: api-gateway → /api/inventory/**
  → inventory-service:stable (90%)
  → inventory-service-canary:canary (10%)

CanaryState: IN_PROGRESS, weight=10
```

### Evaluator тики → Фаза 2

Каждые 30 секунд evaluator проверяет метрики.

Если canary error rate ≤ 5% три раза подряд → weight 10→20:

```
Routes:
  → inventory-service:stable (80%)
  → inventory-service-canary:canary (20%)

CanaryState: IN_PROGRESS, weight=20
```

Продолжается: 20→30→40→50→60→70→80→90→100.

### Weight достиг 100% → Promote

```
1. Patch: inventory-service deployment image → v2
2. Kubernetes rolling update: Pod 1 v1→v2, Pod 2 v1→v2
3. Delete: inventory-service-canary deployment
4. Delete: inventory-service-canary service
5. Routes: inventory-service:stable (100%), canary destination удалён
6. CanaryState: PROMOTED
```

Конечное состояние:

```
Deployment: inventory-service (replicas: 2, image: v2)
  Pod 1: inventory v2 + sidecar
  Pod 2: inventory v2 + sidecar

Routes: api-gateway → /api/inventory/** → inventory-service:stable (100%)

CanaryState: PROMOTED
```

### Если canary error rate > 5% → Rollback

На любом этапе, если error rate превышает порог:

```
1. Delete: inventory-service-canary deployment
2. Delete: inventory-service-canary service
3. Routes: inventory-service:stable (100%), canary destination удалён
4. CanaryState: ROLLED_BACK
```

Система мгновенно возвращается к исходному состоянию.

---

## Логирование

| Уровень | Событие |
|---|---|
| INFO | `"Control plane started. Loaded {n} services, {m} routes from {path}"` |
| INFO | `"Config reloaded. Version: {v}, services: {n}, routes: {m}"` |
| DEBUG | `"Config requested by serviceId={id}. Returning {n} routes, version={v}"` |
| INFO | `"Metrics report received from {proxyId}: {n} entries"` |
| INFO | `"Canary started for {serviceId}: image={image}, initialWeight={w}"` |
| INFO | `"Canary evaluation: {serviceId} canary errorRate={rate}%, threshold={t}%"` |
| INFO | `"Canary weight increased: {serviceId} {oldWeight}% → {newWeight}%"` |
| WARN | `"Canary evaluation: insufficient data for {serviceId}, skipping"` |
| INFO | `"Canary promoted: {serviceId} → {canaryVersion}"` |
| WARN | `"Canary rollback: {serviceId}, reason: error rate {rate}% > threshold {t}%"` |
| ERROR | `"K8s API error: {operation} {resource} → {code} {message}"` |
| WARN | `"Config reload failed: {reason}. Keeping current config version={v}"` |

---

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

### Сборка

```bash
# Из директории control-plane/
mvn clean package -DskipTests
docker build -t mesh-control-plane:latest .
```

Для локальной работы с OrbStack образ сразу доступен в кластере без push в registry.

---

## Kubernetes манифесты

### Namespace

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: mesh
```

### ConfigMap с mesh-config.yaml

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: mesh-config
  namespace: mesh
data:
  mesh-config.yaml: |
    services:
      - id: api-gateway
        host: api-gateway.mesh.svc.cluster.local
        port: 8080
        healthPath: /health
      - id: inventory-service
        host: inventory-service.mesh.svc.cluster.local
        port: 8080
        healthPath: /health
    routes:
      - source: api-gateway
        pathPattern: /api/inventory/**
        destinations:
          - serviceId: inventory-service
            host: inventory-service.mesh.svc.cluster.local
            port: 8080
            version: stable
            weight: 100
    retryPolicy:
      maxAttempts: 3
      delayMs: 500
      retriableStatusCodes: [502, 503, 504]
```

### ServiceAccount + RBAC

Control plane нуждается в правах для управления Deployments и Services:

```yaml
apiVersion: v1
kind: ServiceAccount
metadata:
  name: mesh-control-plane
  namespace: mesh
---
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: mesh-control-plane-role
  namespace: mesh
rules:
  - apiGroups: ["apps"]
    resources: ["deployments"]
    verbs: ["get", "list", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["services"]
    verbs: ["get", "list", "create", "update", "patch", "delete"]
  - apiGroups: [""]
    resources: ["pods"]
    verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: mesh-control-plane-binding
  namespace: mesh
subjects:
  - kind: ServiceAccount
    name: mesh-control-plane
    namespace: mesh
roleRef:
  kind: Role
  name: mesh-control-plane-role
  apiGroup: rbac.authorization.k8s.io
```

### Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mesh-control-plane
  namespace: mesh
  labels:
    app: mesh-control-plane
spec:
  replicas: 1
  selector:
    matchLabels:
      app: mesh-control-plane
  template:
    metadata:
      labels:
        app: mesh-control-plane
    spec:
      serviceAccountName: mesh-control-plane
      containers:
        - name: control-plane
          image: mesh-control-plane:latest
          imagePullPolicy: IfNotPresent
          ports:
            - containerPort: 8080
          env:
            - name: MESH_CONFIG_PATH
              value: /config/mesh-config.yaml
            - name: K8S_NAMESPACE
              value: mesh
          volumeMounts:
            - name: mesh-config
              mountPath: /config
              readOnly: true
          resources:
            requests:
              cpu: "100m"
              memory: "128Mi"
            limits:
              cpu: "500m"
              memory: "256Mi"
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          readinessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 10
            periodSeconds: 5
      volumes:
        - name: mesh-config
          configMap:
            name: mesh-config
```

### Service

```yaml
apiVersion: v1
kind: Service
metadata:
  name: mesh-control-plane
  namespace: mesh
  labels:
    app: mesh-control-plane
spec:
  selector:
    app: mesh-control-plane
  ports:
    - name: http
      port: 8080
      targetPort: 8080
  type: ClusterIP
```

Sidecar-прокси обращаются к control plane по адресу: `http://mesh-control-plane.mesh.svc.cluster.local:8080`.

---

## Зависимости в pom.xml

```xml
<dependencies>
    <!-- Web -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Actuator (health endpoint) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- YAML parsing -->
    <dependency>
        <groupId>com.fasterxml.jackson.dataformat</groupId>
        <artifactId>jackson-dataformat-yaml</artifactId>
    </dependency>

    <!-- Kubernetes Java Client -->
    <dependency>
        <groupId>io.kubernetes</groupId>
        <artifactId>client-java</artifactId>
        <version>21.0.1</version>
    </dependency>

    <!-- SpringDoc OpenAPI (Swagger UI) -->
    <dependency>
        <groupId>org.springdoc</groupId>
        <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
        <version>2.6.0</version>
    </dependency>

    <!-- Lombok -->
    <dependency>
        <groupId>org.projectlombok</groupId>
        <artifactId>lombok</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- Test -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Порядок реализации

1. **Maven проект + application.yml + MeshProperties + CanaryProperties** — каркас приложения.
2. **Модели данных** — все record/class из раздела моделей.
3. **ConfigFileLoader** — чтение и парсинг mesh-config.yaml. Тест: положить файл в test/resources, проверить парсинг.
4. **ConfigStore** — in-memory хранилище. Тесты: загрузка, фильтрация по serviceId, обновление весов.
5. **REST API: ConfigController, ServiceController, RouteController** — отдача конфигурации. Тест: поднять приложение, вызвать GET /config?serviceId=api-gateway, проверить ответ.
6. **MetricsStore + MetricsController** — приём метрик. Тест: отправить POST /metrics/report, проверить что данные легли.
7. **KubernetesDeployer** — работа с K8s API. Тестировать вручную в кластере (или mock в unit-тестах).
8. **CanaryManager** — start/promote/rollback. Интеграционный тест в кластере.
9. **CanaryEvaluator** — scheduled оценка. Тест: заполнить MetricsStore данными, вызвать evaluate(), проверить решение.
10. **CanaryController** — REST эндпоинты canary. E2E тест в кластере.
11. **Dockerfile** — собрать образ.
12. **K8s манифесты** — ConfigMap, RBAC, Deployment, Service. Задеплоить, проверить.
13. **Swagger UI** — проверить что /swagger-ui.html работает.


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
