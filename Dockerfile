# syntax=docker/dockerfile:1
# 빌드 호스트에서 만든 Java/프런트엔드 산출물은 두 런타임 아키텍처에서 공유한다.
FROM --platform=$BUILDPLATFORM eclipse-temurin:25-jdk-noble AS build-base
WORKDIR /workspace
COPY . .

FROM build-base AS build-lite
RUN ./gradlew :app:bootJar :app:verifyLiteClasspath --no-daemon -q

FROM build-base AS build-full
RUN ./gradlew -Pfull :app:bootJar --no-daemon -q

FROM eclipse-temurin:25-jre-noble AS runtime
ARG PUID=1000
ARG PGID=1000
LABEL org.opencontainers.image.source="https://github.com/Nasquire/nasquire" \
      org.opencontainers.image.licenses="AGPL-3.0-only" \
      org.opencontainers.image.title="Nasquire"
RUN test "$PUID" -gt 0 && test "$PGID" -gt 0 \
    && apt-get update && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -r /var/lib/apt/lists/* \
    && groupadd --non-unique --gid "$PGID" nasquire \
    && useradd --non-unique --uid "$PUID" --gid "$PGID" --no-create-home nasquire \
    && mkdir -p /data /app && chown "$PUID:$PGID" /data
WORKDIR /data
COPY --chmod=755 deploy/healthcheck.sh /app/healthcheck.sh
ENV NASQUIRE_DATA=/data \
    DJL_CACHE_DIR=/data/models/.djl-cache \
    MALLOC_ARENA_MAX=2 \
    TOKENIZERS_PARALLELISM=false
ENV JAVA_TOOL_OPTIONS="--enable-native-access=ALL-UNNAMED -Xms64m -Xmx256m -XX:+UseSerialGC -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -Xss512k -XX:MaxDirectMemorySize=32m"
USER nasquire:nasquire
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD ["/app/healthcheck.sh"]
ENTRYPOINT ["java", "-jar", "/app/nasquire.jar"]

FROM runtime AS full
USER root
# util-linux는 prlimit·setsid, 글꼴은 OCR 자식 JVM의 PDF 렌더링에 필요하다.
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-kor tesseract-ocr-eng util-linux fontconfig fonts-dejavu-core \
    && rm -r /var/lib/apt/lists/*
COPY --from=build-full /workspace/app/build/libs/app-0.1.0.jar /app/nasquire.jar
USER nasquire:nasquire

# --target 생략 시에도 lite가 기본이다. 모델 파일은 어느 이미지에도 복사하지 않는다.
FROM runtime AS lite
COPY --from=build-lite /workspace/app/build/libs/app-0.1.0.jar /app/nasquire.jar
