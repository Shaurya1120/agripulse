FROM maven:3.9.14-eclipse-temurin-17 AS build
WORKDIR /workspace

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests clean package

FROM eclipse-temurin:17-jre
WORKDIR /app

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS=""

COPY --from=build /workspace/target/agripulse-0.0.1-SNAPSHOT.jar app.jar

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
