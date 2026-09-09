FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /workspace

COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline

COPY src ./src
RUN mvn -B -ntp clean package

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --create-home --uid 10001 gameapp
COPY --from=build /workspace/target/game-sources.jar /app/game-sources.jar

USER gameapp
EXPOSE 8080
ENV HOST=0.0.0.0
ENV PORT=8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/health | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["java", "-jar", "/app/game-sources.jar"]
