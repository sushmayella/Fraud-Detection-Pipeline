# Multi-stage build: compile once, run slim
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cache dependencies first
COPY pom.xml .
COPY shared/events/pom.xml shared/events/
COPY services/ingestion-api/pom.xml services/ingestion-api/
COPY services/feature-extractor/pom.xml services/feature-extractor/
RUN mvn -pl shared/events,services/ingestion-api -am dependency:go-offline -B -q || true

COPY shared/events shared/events
COPY services/ingestion-api services/ingestion-api
RUN mvn -pl services/ingestion-api -am package -DskipTests -B -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /workspace/services/ingestion-api/target/*.jar /app/app.jar
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
