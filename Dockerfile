FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew :app:bootJar --no-daemon -q

FROM eclipse-temurin:25-jre
ARG PUID=1000
ARG PGID=1000
RUN groupadd --gid "$PGID" nasquire && useradd --uid "$PUID" --gid "$PGID" --no-create-home nasquire \
    && mkdir -p /data && chown "$PUID:$PGID" /data
WORKDIR /data
COPY --from=build --chown=nasquire:nasquire /workspace/app/build/libs/app-0.1.0.jar /app/nasquire.jar
ENV NASQUIRE_DATA=/data
ENV JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m"
USER nasquire:nasquire
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/nasquire.jar"]
