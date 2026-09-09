FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn ./.mvn
COPY mvnw pom.xml ./
RUN sh ./mvnw -B -ntp dependency:go-offline

COPY src ./src
RUN sh ./mvnw -B -ntp clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --create-home --uid 10001 gameapp

COPY --from=build /workspace/target/game-sources.jar /app/game-sources.jar

USER gameapp
EXPOSE 8080
ENV HOST=0.0.0.0
ENV PORT=8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/game-sources.jar"]
