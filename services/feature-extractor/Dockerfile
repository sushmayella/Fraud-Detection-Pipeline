# Multi-stage: compile once (heavy JDK image), run slim (JRE-alpine).
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# Cache dependencies first by copying only the poms
COPY pom.xml .
COPY shared/events/pom.xml shared/events/
COPY services/feature-extractor/pom.xml services/feature-extractor/
RUN mvn -pl shared/events,services/feature-extractor -am dependency:go-offline -B -q || true

COPY shared/events shared/events
COPY services/feature-extractor services/feature-extractor
RUN mvn -pl services/feature-extractor -am package -DskipTests -B -q

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build /workspace/services/feature-extractor/target/*.jar /app/app.jar
EXPOSE 8082
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s \
  CMD wget -qO- http://localhost:8082/actuator/health || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
