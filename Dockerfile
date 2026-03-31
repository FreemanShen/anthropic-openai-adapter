FROM maven:3.8.8-eclipse-temurin-8 AS builder

WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:8-jre

WORKDIR /app
COPY --from=builder /build/target/anthropic-adapter-1.0.0.jar /app/anthropic-adapter.jar

ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_OPTS=""

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar /app/anthropic-adapter.jar --spring.profiles.active=${SPRING_PROFILES_ACTIVE}"]
