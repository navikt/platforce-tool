FROM gradle:9.8.2-jdk21 AS gradle
FROM node:24.22.3-bookworm-slim AS node

FROM eclipse-temurin:21-jre-ubi10-minimal

RUN microdnf install -y libstdc++ libatomic \
    && microdnf clean all

COPY --from=gradle /opt/gradle /opt/gradle
COPY --from=node /usr/local/bin/node /opt/node/bin/node
COPY --from=node /usr/local/lib/node_modules/npm /opt/node/lib/node_modules/npm

ENV PLATFORCE_NODE_HOME=/opt/node
ENV PATH="/opt/node/bin:${PATH}"

RUN node --version \
    && node /opt/node/lib/node_modules/npm/bin/npm-cli.js --version

COPY build/libs/app*.jar app.jar

ENTRYPOINT ["java", "-Dlogback.configurationFile=logback-remote.xml", "-jar", "/app.jar"]
