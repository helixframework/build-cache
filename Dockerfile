FROM gradle:8.10.2-jdk17 AS build
WORKDIR /workspace
COPY . .
RUN gradle --no-daemon clean bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
RUN mkdir -p /data/cache
VOLUME ["/data/cache"]
COPY --from=build /workspace/build/libs/*.jar /app/app.jar
EXPOSE 5071
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
