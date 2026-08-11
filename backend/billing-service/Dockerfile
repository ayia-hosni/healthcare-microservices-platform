FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY common/pom.xml /workspace/common/pom.xml
COPY billing-service/pom.xml /workspace/billing-service/pom.xml
COPY identity-service/pom.xml /workspace/identity-service/pom.xml
COPY patient-service/pom.xml /workspace/patient-service/pom.xml
COPY doctor-service/pom.xml /workspace/doctor-service/pom.xml
COPY appointment-service/pom.xml /workspace/appointment-service/pom.xml
COPY emr-service/pom.xml /workspace/emr-service/pom.xml
COPY notification-service/pom.xml /workspace/notification-service/pom.xml
COPY audit-service/pom.xml /workspace/audit-service/pom.xml
COPY analytics-service/pom.xml /workspace/analytics-service/pom.xml
RUN mvn -q -f pom.xml -pl common,billing-service -am dependency:go-offline
COPY common /workspace/common
COPY billing-service /workspace/billing-service
RUN mvn -q -f pom.xml -pl billing-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/billing-service/target/*.jar app.jar
EXPOSE 8086
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8086/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
