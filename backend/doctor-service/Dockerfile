FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY common/pom.xml /workspace/common/pom.xml
COPY doctor-service/pom.xml /workspace/doctor-service/pom.xml
RUN mvn -q -f pom.xml -pl common,doctor-service -am dependency:go-offline
COPY common /workspace/common
COPY doctor-service /workspace/doctor-service
RUN mvn -q -f pom.xml -pl doctor-service -am package -DskipTests

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/doctor-service/target/*.jar app.jar
EXPOSE 8083
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8083/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
