FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 gameapp \
    && mkdir -p /data/files \
    && chown -R gameapp:gameapp /data

COPY --from=build /workspace/target/game-sources.jar /app/game-sources.jar

USER gameapp
EXPOSE 8080
ENV HOST=0.0.0.0
ENV PORT=8080
ENV FILE_STORAGE_DIR=/data/files
ENV MAX_UPLOAD_BYTES=104857600
ENV FILE_PUBLIC_DOWNLOADS=false

HEALTHCHECK --interval=5s --timeout=3s --start-period=3s --retries=5 \
  CMD curl -fsS "http://127.0.0.1:${PORT:-8080}/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/game-sources.jar"]
