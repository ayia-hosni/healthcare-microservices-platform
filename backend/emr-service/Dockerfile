FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -N -f pom.xml install

COPY common/pom.xml /workspace/common/pom.xml
COPY common/src /workspace/common/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f common/pom.xml install -Dmaven.test.skip=true

COPY emr-service/pom.xml /workspace/emr-service/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -f emr-service/pom.xml dependency:resolve

COPY emr-service/src /workspace/emr-service/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f emr-service/pom.xml package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/emr-service/target/*.jar app.jar
EXPOSE 8085
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8085/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
