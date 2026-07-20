FROM maven:3.9.9-eclipse-temurin-8 AS build

WORKDIR /workspace
COPY pom.xml ./
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests

FROM eclipse-temurin:8-jre-jammy

RUN groupadd --system fanzha && useradd --system --gid fanzha --home-dir /app fanzha
WORKDIR /app
COPY --from=build --chown=fanzha:fanzha /workspace/target/fanzha-backend.jar app.jar

USER fanzha
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
