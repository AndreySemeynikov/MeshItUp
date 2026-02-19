# Sidecar Proxy — Техническое задание и спецификация реализации

## Контекст проекта

Мы разрабатываем учебный Service Mesh с нуля. Sidecar Proxy — компонент data plane, который разворачивается вторым контейнером в каждом Kubernetes Pod рядом с бизнес-сервисом. Один и тот же Docker-образ sidecar используется для всех подов — различия только в переменных окружения.

### Общая архитектура mesh

```
Пользователь → [API Gateway + Sidecar] → [Inventory Service + Sidecar]
                                                     ↑
                                               Sidecar решает:
                                               stable (90%) или canary (10%)
```

- **API Gateway** (Go) — точка входа, вызывает Inventory Service через свой sidecar
- **Inventory Service** (Go) — конечный сервис, на нём проводится canary release
- **Sidecar Proxy** (Java/Spring Boot) — данная спецификация
- **Control Plane** (Java/Spring Boot) — отдельный сервис, хранит конфигурацию и управляет canary

Sidecar **не знает** о бизнес-логике сервиса рядом с ним. Он видит только HTTP-запросы (method, path, headers, status code) и работает по конфигурации от control plane.

### Принцип работы

Бизнес-сервис отправляет все исходящие HTTP-запросы не напрямую к другим сервисам, а на `localhost:15001` (свой sidecar). Sidecar по конфигурации определяет куда направить запрос, пересылает его, получает ответ и возвращает сервису. Параллельно собирает метрики каждого вызова.

---

## Технологический стек

| Компонент | Технология | Версия |
|---|---|---|
| Язык | Java | 21 |
| Фреймворк | Spring Boot | 3.2+ |
| HTTP-клиент | RestClient (Spring Boot 3.2) | — |
| Метрики | Micrometer + Prometheus registry | — |
| Path matching | AntPathMatcher (Spring) | — |
| Сборка | Maven | — |
| Контейнеризация | Docker (eclipse-temurin:21-jre) | — |
| JVM параметры | -XX:MaxRAMPercentage=75 | — |

**Почему не WebFlux:** проект использует обычный Spring MVC с RestClient для простоты. Нет необходимости в реактивном стеке — нагрузка на sidecar в учебном проекте невысокая, а синхронная модель проще для отладки.

**Почему не Feign:** Feign заточен под декларативные интерфейсы к известным API. Sidecar проксирует произвольные запросы с произвольными path — RestClient подходит лучше.

---

## Конфигурация через переменные окружения

Sidecar при запуске получает настройки через ENV. Это единственный способ параметризации — никаких конфиг-файлов на диске.

| Переменная | Обязательная | Default | Описание |
|---|---|---|---|
| `MESH_SERVICE_ID` | Да | — | Идентификатор сервиса рядом (например `api-gateway`, `inventory-service`). Передаётся в control plane при запросе конфигурации и в отчётах метрик. |
| `MESH_CONTROL_PLANE_URL` | Да | — | Полный URL control plane (например `http://control-plane.mesh.svc.cluster.local:8080`). |
| `PROXY_PORT` | Нет | `15001` | Порт на котором sidecar слушает HTTP-запросы от бизнес-сервиса. |
| `METRICS_PORT` | Нет | `15002` | Порт для Prometheus endpoint (`/actuator/prometheus`). |
| `CONFIG_REFRESH_INTERVAL` | Нет | `10` | Интервал в секундах между запросами конфигурации к control plane. |
| `METRICS_REPORT_INTERVAL` | Нет | `30` | Интервал в секундах между отправками агрегированных метрик в control plane. |

В `application.yml` эти переменные маппятся через стандартный Spring Boot механизм:

```yaml
mesh:
  service-id: ${MESH_SERVICE_ID}
  control-plane-url: ${MESH_CONTROL_PLANE_URL}
  proxy-port: ${PROXY_PORT:15001}
  metrics-port: ${METRICS_PORT:15002}
  config-refresh-interval: ${CONFIG_REFRESH_INTERVAL:10}
  metrics-report-interval: ${METRICS_REPORT_INTERVAL:30}
```

Эти значения маппятся в `@ConfigurationProperties`-класс `MeshProperties`.

---

## Структура проекта

```
sidecar-proxy/
├── pom.xml
├── Dockerfile
└── src/main/java/com/mesh/sidecar/
    ├── SidecarApplication.java              — точка входа (@SpringBootApplication)
    ├── config/
    │   └── MeshProperties.java              — @ConfigurationProperties, маппинг ENV
    ├── model/
    │   ├── MeshConfig.java                  — полная конфигурация от control plane
    │   ├── RouteDefinition.java             — один маршрут (path, destinations, weights)
    │   ├── Destination.java                 — один destination (host, port, version, weight)
    │   ├── RetryPolicy.java                 — параметры retry (maxAttempts, delay, retriableCodes)
    │   └── MetricsReport.java               — агрегированный отчёт для control plane
    ├── sync/
    │   └── ConfigSyncService.java           — периодический pull конфигурации
    ├── routing/
    │   └── Router.java                      — выбор destination по path + weighted random
    ├── forwarding/
    │   └── HttpForwarder.java               — пересылка HTTP-запроса + retry
    ├── metrics/
    │   └── MetricsCollector.java            — Micrometer-метрики + отчёты в control plane
    └── proxy/
        └── ProxyController.java             — @RestController, точка входа запросов
```

---

## Модели данных

### MeshConfig — конфигурация от control plane

Приходит в ответ на `GET /api/v1/config?serviceId={MESH_SERVICE_ID}`.

```json
{
  "version": 5,
  "routes": [
    {
      "source": "api-gateway",
      "pathPattern": "/api/inventory/**",
      "destinations": [
        {
          "host": "inventory-service.mesh.svc.cluster.local",
          "port": 8080,
          "version": "stable",
          "weight": 90
        },
        {
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

**Поля:**
- `version` (int) — номер версии конфигурации. Sidecar сравнивает с текущей версией и обновляет локальное состояние только если version изменился.
- `routes` (List<RouteDefinition>) — список маршрутов, релевантных данному сервису. Control plane фильтрует по `source == MESH_SERVICE_ID`.
- `retryPolicy` (RetryPolicy) — глобальная политика retry для всех маршрутов.

### RouteDefinition

```java
public record RouteDefinition(
    String source,             // кто отправляет (e.g. "api-gateway")
    String pathPattern,        // AntPath паттерн (e.g. "/api/inventory/**")
    List<Destination> destinations  // куда можно отправить с весами
) {}
```

### Destination

```java
public record Destination(
    String host,       // K8s DNS имя (e.g. "inventory-service.mesh.svc.cluster.local")
    int port,          // порт сервиса (e.g. 8080)
    String version,    // метка версии ("stable" или "canary")
    int weight         // вес от 0 до 100
) {}
```

### RetryPolicy

```java
public record RetryPolicy(
    int maxAttempts,              // максимум попыток (включая первую)
    long delayMs,                 // пауза между попытками в мс
    List<Integer> retriableStatusCodes  // при каких кодах повторять (502, 503, 504)
) {}
```

### MetricsReport — отчёт для control plane

Отправляется через `POST /api/v1/metrics/report`.

```json
{
  "proxyId": "api-gateway-sidecar",
  "serviceId": "api-gateway",
  "timestamp": "2025-01-15T12:00:30Z",
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

Это именно те данные, по которым control plane принимает решение о promote/rollback canary.

---

## Модуль 1: ConfigSyncService

### Ответственность
Периодически запрашивает конфигурацию у control plane и обновляет локальное хранилище.

### Поведение

1. При старте приложения — немедленно выполняет первый запрос конфигурации (eager init).
2. Далее каждые `CONFIG_REFRESH_INTERVAL` секунд выполняет `GET {MESH_CONTROL_PLANE_URL}/api/v1/config?serviceId={MESH_SERVICE_ID}`.
3. Парсит ответ в `MeshConfig`.
4. Сравнивает `version` с текущей конфигурацией. Если version изменился — обновляет `AtomicReference<MeshConfig>`.
5. Если control plane недоступен (connection refused, timeout, 5xx) — логирует WARN и **продолжает работать** с последней успешной конфигурацией.
6. Если ещё ни одна конфигурация не была получена (первый запуск, control plane не готов) — sidecar возвращает 503 на все входящие запросы с телом `{"error": "sidecar not configured yet"}`.

### Реализация

- `@Scheduled(fixedDelayString = "${mesh.config-refresh-interval}000")` на методе `sync()`.
- `RestClient` для HTTP-вызова.
- `AtomicReference<MeshConfig>` для потокобезопасного хранения.
- Метод `getConfig()` — public, возвращает текущий `MeshConfig`. Вызывается из Router.

---

## Модуль 2: Router

### Ответственность
По входящему HTTP-запросу определяет конкретный destination (host:port + version).

### Поведение

1. Получает на вход: HTTP method, request path, headers.
2. Берёт текущий `MeshConfig` из `ConfigSyncService.getConfig()`.
3. Перебирает `routes` и ищет первый маршрут, у которого `pathPattern` совпадает с request path. Использует `AntPathMatcher.match()`.
4. Из найденного маршрута берёт список `destinations` с весами.
5. Выполняет **weighted random**: генерирует `ThreadLocalRandom.current().nextInt(100)`, проходит по destinations суммируя веса, выбирает тот, в чей диапазон попало число.
6. Возвращает выбранный `Destination` (host, port, version).
7. Если ни один маршрут не совпал — возвращает `Optional.empty()`. ProxyController в этом случае вернёт 503.

### Weighted random — алгоритм

```
destinations: [stable(90), canary(10)]
random = ThreadLocalRandom.current().nextInt(100)  // 0..99

cumulative = 0
for each destination:
    cumulative += destination.weight
    if random < cumulative:
        return destination

// stable: 0..89 → stable
// canary: 90..99 → canary
```

### Важно

- Router — stateless. Не хранит состояния между вызовами.
- Router — чистая логика. Не делает HTTP-вызовов. Легко покрывается unit-тестами.
- `AntPathMatcher` из пакета `org.springframework.util`.

---

## Модуль 3: HttpForwarder

### Ответственность
Пересылает HTTP-запрос от бизнес-сервиса к целевому destination и возвращает ответ. Реализует retry.

### Поведение

1. Получает на вход: оригинальный `HttpServletRequest`, выбранный `Destination`, `RetryPolicy` из конфигурации.
2. Формирует URL: `http://{destination.host}:{destination.port}{originalPath}`.
3. Копирует из оригинального запроса: HTTP method, path, query string, body, headers (кроме `Host`).
4. Добавляет mesh-заголовки:
   - `X-Request-Id` — если отсутствует во входящем запросе, генерирует `UUID.randomUUID()`. Если присутствует — прокидывает как есть.
   - `X-Mesh-Source` — значение `MESH_SERVICE_ID`.
   - `X-Mesh-Route-Version` — `stable` или `canary` (из `destination.version`).
5. Отправляет запрос через `RestClient`.
6. Засекает время (`System.nanoTime()` до и после) для расчёта latency.

### Retry логика

- Если ответ содержит status code из `retryPolicy.retriableStatusCodes` ИЛИ произошёл `ResourceAccessException` (connection refused, timeout):
  - Ждёт `retryPolicy.delayMs` миллисекунд (`Thread.sleep`).
  - Повторяет запрос.
  - Максимум `retryPolicy.maxAttempts` попыток (включая первую).
  - Каждая повторная попытка логируется: `WARN "Retry {attempt}/{max} for {method} {url}, reason: {statusCode or exception}"`.
- Если все попытки исчерпаны — возвращает последний полученный ответ (или 502 Bad Gateway при connection error).

### Возврат ответа

- HTTP status code от целевого сервиса копируется как есть.
- Headers ответа копируются (кроме `Transfer-Encoding`, `Connection`).
- Body ответа копируется как есть (byte[]).
- Sidecar полностью прозрачен — бизнес-сервис получает ровно то, что ответил downstream.

---

## Модуль 4: MetricsCollector

### Ответственность
Собирает метрики каждого проксированного запроса. Экспортирует в двух форматах: Prometheus (для Grafana) и агрегированные отчёты (для control plane).

### Часть A: Prometheus-метрики через Micrometer

После каждого обработанного запроса ProxyController вызывает `MetricsCollector.record(...)` с параметрами:

- `destination` (String) — имя целевого сервиса (e.g. `inventory-service`)
- `version` (String) — `stable` или `canary`
- `statusCode` (int) — HTTP status code ответа
- `durationMs` (long) — latency в миллисекундах
- `retryCount` (int) — сколько retry было выполнено (0 если с первого раза)

Регистрируемые метрики:

| Метрика | Тип Micrometer | Tags | Описание |
|---|---|---|---|
| `mesh_proxy_requests_total` | Counter | destination, version, status | Общее количество запросов |
| `mesh_proxy_request_duration_ms` | Timer | destination, version | Время обработки запроса |
| `mesh_proxy_errors_total` | Counter | destination, version | Количество ошибок (status >= 500) |
| `mesh_proxy_retries_total` | Counter | destination, version | Количество retry-попыток |

Эти метрики автоматически доступны на `http://localhost:{METRICS_PORT}/actuator/prometheus`.

Для этого в `pom.xml` добавляются зависимости:
- `spring-boot-starter-actuator`
- `micrometer-registry-prometheus`

В `application.yml`:
```yaml
management:
  server:
    port: ${METRICS_PORT:15002}
  endpoints:
    web:
      exposure:
        include: prometheus,health
  metrics:
    tags:
      application: mesh-sidecar
      service: ${MESH_SERVICE_ID}
```

### Часть B: Агрегированные отчёты для control plane

Каждые `METRICS_REPORT_INTERVAL` секунд MetricsCollector:

1. Агрегирует данные за прошедший интервал: для каждой пары (destination, version) считает requestCount, errorCount, avgLatencyMs.
2. Формирует `MetricsReport` JSON.
3. Отправляет `POST {MESH_CONTROL_PLANE_URL}/api/v1/metrics/report`.
4. Сбрасывает внутренние счётчики для следующего окна.

Для агрегации используется внутренний `ConcurrentHashMap<String, RequestStats>`, где ключ — `"{destination}:{version}"`, а `RequestStats` содержит атомарные счётчики (`AtomicInteger count`, `AtomicInteger errors`, `AtomicLong totalLatency`).

**Важно:** Micrometer-метрики (часть A) **не сбрасываются** — они кумулятивные, как ожидает Prometheus. Сбрасываются только внутренние счётчики для отчётов в control plane (часть B).

Реализация — `@Scheduled(fixedDelayString = "${mesh.metrics-report-interval}000")`.

---

## ProxyController — точка входа запросов

### Ответственность
Единственный `@RestController`. Принимает **все** HTTP-запросы от бизнес-сервиса и оркестрирует обработку.

### Маппинг

```java
@RequestMapping("/**")
```

Принимает любой HTTP method (GET, POST, PUT, DELETE, PATCH) и любой path. Sidecar не знает заранее, какие эндпоинты вызывает бизнес-сервис.

### Логика обработки запроса

```
1. Получить HttpServletRequest
2. Если ConfigSyncService.getConfig() == null → вернуть 503 "sidecar not configured"
3. Router.resolve(method, path, headers) → Optional<Destination>
4. Если Optional.empty() → вернуть 503 "no matching route"
5. HttpForwarder.forward(request, destination, retryPolicy) → ForwardResult
6. MetricsCollector.record(destination, version, statusCode, durationMs, retryCount)
7. Вернуть ответ клиенту (status + headers + body)
```

### ForwardResult — результат пересылки

```java
public record ForwardResult(
    int statusCode,
    Map<String, String> headers,
    byte[] body,
    long durationMs,
    int retryCount
) {}
```

---

## Health Check

Sidecar должен предоставить health endpoint для Kubernetes liveness/readiness probes.

```yaml
# В Kubernetes deployment:
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 15002
  initialDelaySeconds: 10
readinessProbe:
  httpGet:
    path: /actuator/health
    port: 15002
  initialDelaySeconds: 5
```

Readiness должен учитывать, получена ли хотя бы одна конфигурация от control plane. Если нет — readiness = DOWN. Это реализуется через custom `HealthIndicator`:

```java
@Component
public class ConfigHealthIndicator implements HealthIndicator {
    private final ConfigSyncService configSyncService;

    @Override
    public Health health() {
        if (configSyncService.getConfig() == null) {
            return Health.down().withDetail("reason", "no config received").build();
        }
        return Health.up().build();
    }
}
```

---

## Dockerfile

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY target/*.jar app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"
EXPOSE 15001 15002
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

Ожидаемый размер образа: ~200 MB (из них ~180 MB — JRE).
Ожидаемое потребление RAM: ~100-128 MB с ограничением MaxRAMPercentage=75.

---

## Зависимости в pom.xml

```xml
<dependencies>
    <!-- Web (Spring MVC, embedded Tomcat) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- Actuator (health, prometheus endpoint) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>

    <!-- Micrometer Prometheus registry -->
    <dependency>
        <groupId>io.micrometer</groupId>
        <artifactId>micrometer-registry-prometheus</artifactId>
    </dependency>

    <!-- Lombok (опционально, для сокращения boilerplate) -->
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

## application.yml

```yaml
spring:
  application:
    name: mesh-sidecar

server:
  port: ${PROXY_PORT:15001}

management:
  server:
    port: ${METRICS_PORT:15002}
  endpoints:
    web:
      exposure:
        include: prometheus,health
  metrics:
    tags:
      application: mesh-sidecar
      service: ${MESH_SERVICE_ID:unknown}

mesh:
  service-id: ${MESH_SERVICE_ID}
  control-plane-url: ${MESH_CONTROL_PLANE_URL}
  proxy-port: ${PROXY_PORT:15001}
  metrics-port: ${METRICS_PORT:15002}
  config-refresh-interval: ${CONFIG_REFRESH_INTERVAL:10}
  metrics-report-interval: ${METRICS_REPORT_INTERVAL:30}

logging:
  level:
    com.mesh.sidecar: INFO
    com.mesh.sidecar.sync: DEBUG
```

---

## Логирование

Sidecar логирует ключевые события для отладки:

| Уровень | Событие |
|---|---|
| INFO | Sidecar запущен: `"Sidecar started for service={serviceId}, proxy port={port}"` |
| DEBUG | Конфигурация обновлена: `"Config updated to version={version}, routes={count}"` |
| WARN | Control plane недоступен: `"Config sync failed: {reason}. Using cached config v{version}"` |
| WARN | Retry: `"Retry {attempt}/{max} for {method} {path} → {destination}, reason: {code}"` |
| INFO | Каждый запрос: `"{method} {path} → {destination}:{port} [{version}] → {statusCode} ({durationMs}ms)"` |
| ERROR | Все retry исчерпаны: `"All retries exhausted for {method} {path} → {destination}"` |
| INFO | Метрики отправлены: `"Metrics report sent to control plane: {entries} entries"` |

---

## Как sidecar используется в Kubernetes Pod

Пример фрагмента Deployment для API Gateway:

```yaml
spec:
  containers:
    # Бизнес-сервис
    - name: api-gateway
      image: api-gateway:latest
      ports:
        - containerPort: 8080
      env:
        - name: INVENTORY_SERVICE_URL
          value: "http://localhost:15001"   # ← направляет запросы в свой sidecar
    
    # Sidecar Proxy
    - name: mesh-sidecar
      image: mesh-sidecar:latest
      ports:
        - containerPort: 15001
        - containerPort: 15002
      env:
        - name: MESH_SERVICE_ID
          value: "api-gateway"
        - name: MESH_CONTROL_PLANE_URL
          value: "http://control-plane.mesh.svc.cluster.local:8080"
      resources:
        requests:
          cpu: "50m"
          memory: "64Mi"
        limits:
          cpu: "200m"
          memory: "128Mi"
```

Ключевой момент: бизнес-сервис настроен на `http://localhost:15001` вместо реального адреса Inventory Service. Sidecar и сервис делят сетевое пространство внутри пода, поэтому `localhost` работает.

---

## Принципы универсальности

Sidecar спроектирован так, чтобы быть универсальным для любого приложения:

1. **Ноль hardcoded адресов** — все маршруты приходят динамически от control plane.
2. **Один Docker-образ** — разница между подами только в ENV (`MESH_SERVICE_ID`).
3. **Прозрачность** — принимает любой HTTP method и path (`/**`), возвращает ответ от downstream без модификации.
4. **Независимость от языка сервиса** — работает одинаково рядом с Go, Java, Python, Node.js сервисами.
5. **Graceful degradation** — если control plane недоступен, работает по кэшированной конфигурации.
6. **Не знает о canary** — для sidecar нет разницы между stable и canary маршрутом. Он просто видит destinations с весами. Логика canary полностью в control plane.

---

## Порядок реализации (рекомендуемый)

1. **Создать Maven проект** с зависимостями и `application.yml`.
2. **MeshProperties** — `@ConfigurationProperties` для маппинга ENV.
3. **Модели данных** — `MeshConfig`, `RouteDefinition`, `Destination`, `RetryPolicy`, `MetricsReport`, `ForwardResult`.
4. **ConfigSyncService** — scheduled pull конфигурации. На этом этапе можно тестировать с mock control plane (просто JSON на любом HTTP сервере).
5. **Router** — weighted random + AntPathMatcher. Покрыть unit-тестами.
6. **HttpForwarder** — RestClient + retry. Тестировать с реальным HTTP сервером.
7. **MetricsCollector** — Micrometer-метрики + отчёты. Проверить что `/actuator/prometheus` отдаёт данные.
8. **ProxyController** — связать все модули. Интеграционный тест: запустить sidecar + mock downstream → отправить запрос → проверить ответ и метрики.
9. **Dockerfile** — собрать образ, проверить запуск.
10. **Kubernetes** — добавить sidecar в поды, проверить e2e.
