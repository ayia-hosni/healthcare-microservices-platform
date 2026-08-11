FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY common/pom.xml /workspace/common/pom.xml
COPY analytics-service/pom.xml /workspace/analytics-service/pom.xml
RUN mvn -q -f pom.xml -pl common,analytics-service -am dependency:go-offline
COPY common /workspace/common
COPY analytics-service /workspace/analytics-service
RUN mvn -q -f pom.xml -pl analytics-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/analytics-service/target/*.jar app.jar
EXPOSE 8089
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8089/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
