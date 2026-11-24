# syntax=docker/dockerfile:1

# --- Build stage -----------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
COPY config.example.json .
RUN mvn -B -q package

# --- Runtime stage ---------------------------------------------------------------------------
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home llmproxy
WORKDIR /app
COPY --from=build /build/target/llm-proxy.jar /app/llm-proxy.jar

# Mount your configuration here (or point LLM_PROXY_CONFIG elsewhere).
ENV LLM_PROXY_CONFIG=/config/config.json \
    LLM_PROXY_HOST=0.0.0.0 \
    LLM_PROXY_PORT=4000 \
    LOG_LEVEL=INFO

USER llmproxy
EXPOSE 4000
ENTRYPOINT ["java", "-XX:+UseSerialGC", "-XX:MaxRAMPercentage=75", "-jar", "/app/llm-proxy.jar"]
