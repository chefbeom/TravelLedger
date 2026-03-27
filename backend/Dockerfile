FROM eclipse-temurin:17-jdk-alpine AS build

WORKDIR /workspace

COPY gradlew .
COPY gradle gradle
COPY build.gradle settings.gradle ./

RUN chmod +x gradlew

COPY src src

RUN ./gradlew --no-daemon clean bootWar

FROM eclipse-temurin:17-jre-alpine

RUN apk add --no-cache curl \
    && addgroup -S spring \
    && adduser -S spring -G spring

WORKDIR /app

COPY --from=build /workspace/build/libs/*.war /app/app.war

RUN mkdir -p /app/uploads \
    && chown -R spring:spring /app

VOLUME ["/app/uploads"]

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["java", "-Duser.dir=/app", "-jar", "/app/app.war"]
