FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Копируем собранный JAR
COPY target/sidecar-proxy-1.0.0.jar app.jar

# Порты: outbound proxy, metrics, inbound proxy
EXPOSE 15001 15002 15006

# JVM параметры: ограничиваем потребление RAM
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/app.jar"]
