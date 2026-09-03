FROM gradle:8.14.4-jdk21 AS gradle

FROM eclipse-temurin:21-jre-ubi10-minimal

COPY --from=gradle /opt/gradle /opt/gradle

COPY build/libs/app*.jar app.jar

ENTRYPOINT ["java", "-Dlogback.configurationFile=logback-remote.xml", "-jar", "/app.jar"]
