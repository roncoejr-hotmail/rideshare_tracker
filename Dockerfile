FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:25-jre
RUN apt-get update && apt-get install -y --no-install-recommends curl \
  && rm -rf /var/lib/apt/lists/* \
  && groupadd -r -g 10002 app && useradd -r -u 10002 -g app -d /app -s /usr/sbin/nologin app \
  && mkdir -p /app /data && chown app:app /app /data
WORKDIR /app
COPY --from=build /app/target/transport-ledger-*.jar app.jar
RUN chown app:app app.jar
EXPOSE 8080
VOLUME /data
ENV TRANSPORT_DB_PATH=/data/transport.db \
    TRANSPORT_BIND=0.0.0.0 \
    TRANSPORT_PORT=8080
USER app
HEALTHCHECK --interval=15s --timeout=5s --retries=5 --start-period=40s \
  CMD ["curl", "-fsS", "http://localhost:8080/health"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "app.jar"]
