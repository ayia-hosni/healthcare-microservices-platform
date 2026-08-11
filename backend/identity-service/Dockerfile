# --- Build stage ---
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace

# Leverage layer caching: copy poms first, resolve deps, then copy sources
COPY pom.xml /workspace/pom.xml
COPY common/pom.xml /workspace/common/pom.xml
COPY identity-service/pom.xml /workspace/identity-service/pom.xml
RUN mvn -q -f pom.xml -pl common,identity-service -am dependency:go-offline

COPY common /workspace/common
COPY identity-service /workspace/identity-service
RUN mvn -q -f pom.xml -pl identity-service -am package -DskipTests

# --- Runtime stage ---
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/identity-service/target/*.jar app.jar
EXPOSE 8081
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
