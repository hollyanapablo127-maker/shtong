# syntax=docker/dockerfile:1.7
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /workspace

COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn
COPY services/gateway/pom.xml services/gateway/pom.xml
COPY services/gateway/src services/gateway/src
RUN ./mvnw -B -pl services/gateway -am -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S -g 10001 shtong && adduser -S -D -H -u 10001 -G shtong shtong
WORKDIR /app
COPY --from=build /workspace/services/gateway/target/shtong-gateway-*.jar /app/shtong-gateway.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djava.io.tmpdir=/tmp", "-jar", "/app/shtong-gateway.jar"]
