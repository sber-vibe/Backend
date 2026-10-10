# syntax=docker/dockerfile:1
FROM eclipse-temurin:26-jdk AS build
WORKDIR /app
COPY gradle gradle
COPY gradlew build.gradle settings.gradle ./
# CRLF из рабочей копии Windows ломает shebang — нормализуем на всякий случай
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew
# зависимости отдельным слоем + кэш Gradle между сборками
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies > /dev/null
COPY src src
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar

FROM eclipse-temurin:26-jre
WORKDIR /app
COPY --from=build /app/build/libs/backend.jar app.jar
RUN mkdir -p /app/data && chown 10001:10001 /app/data
USER 10001
EXPOSE 8080
# JVM берёт память от лимита контейнера, а не от памяти хоста
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
# curl в образе нет — проверяем actuator через /dev/tcp
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=12 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT:-8080} && printf "GET /actuator/health HTTP/1.0\r\nHost: localhost\r\n\r\n" >&3 && grep -q "\"UP\"" <&3'
ENTRYPOINT ["java", "-jar", "app.jar"]
