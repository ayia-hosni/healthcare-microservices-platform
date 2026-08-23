FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml /workspace/pom.xml
COPY graphql-gateway/pom.xml /workspace/graphql-gateway/pom.xml
RUN --mount=type=cache,target=/root/.m2 mvn -q -f graphql-gateway/pom.xml dependency:resolve

COPY graphql-gateway/src /workspace/graphql-gateway/src
RUN --mount=type=cache,target=/root/.m2 mvn -q -f graphql-gateway/pom.xml package -Dmaven.test.skip=true

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
USER spring:spring
WORKDIR /app
COPY --from=build /workspace/graphql-gateway/target/*.jar app.jar
EXPOSE 8090
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget -qO- http://localhost:8090/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
