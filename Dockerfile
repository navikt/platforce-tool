FROM gradle:8.11.1-jdk21 AS gradle

FROM gcr.io/distroless/java21

COPY --from=gradle /opt/gradle /opt/gradle

COPY build/libs/app*.jar app.jar

ENTRYPOINT ["java", "-Dlogback.configurationFile=logback-remote.xml", "-jar", "/app.jar"]
