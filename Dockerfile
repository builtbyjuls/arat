FROM eclipse-temurin:21.0.12_8-jdk-noble AS build

WORKDIR /build

RUN apt-get update \
    && apt-get install --no-install-recommends --yes unzip \
    && rm -rf /var/lib/apt/lists/*

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY src/ src/

RUN chmod +x mvnw \
    && ./mvnw -B -DskipTests package

FROM eclipse-temurin:21.0.12_8-jre-alpine

ARG APP_UID=10001
ARG APP_GID=10001

RUN addgroup -S -g "$APP_GID" arat \
    && adduser -S -D -H -u "$APP_UID" -G arat arat \
    && mkdir -p /tmp/arat \
    && chown "$APP_UID:$APP_GID" /tmp/arat

WORKDIR /app
COPY --from=build --chown="$APP_UID:$APP_GID" /build/target/arat-0.0.1-SNAPSHOT.jar /app/arat.jar

USER $APP_UID:$APP_GID

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://127.0.0.1:8080/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-Djava.io.tmpdir=/tmp/arat", "-jar", "/app/arat.jar"]
