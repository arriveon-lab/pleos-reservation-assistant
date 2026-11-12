FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69 AS build
WORKDIR /workspace
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    chmod +x gradlew && ./gradlew --no-daemon -PincludeAndroid=false :server:previewAcceptanceDist

FROM eclipse-temurin:21-jdk-jammy@sha256:4b1a74fbffbe6b4e7b6f0171a472478282fb463790a51edf10e465d578f22b69
RUN groupadd --gid 10001 arriveon \
    && useradd --uid 10001 --gid 10001 --no-create-home arriveon \
    && mkdir -p /app/work/preview-data \
    && chown -R 10001:10001 /app
WORKDIR /app
COPY --from=build /workspace/server/build/preview-acceptance/ /app/
USER 10001:10001
EXPOSE 18082
ENTRYPOINT ["java", "-Xms64m", "-Xmx384m", "-cp", "classes:lib/*", "ai.pleos.assistant.PreviewAcceptanceApplication"]
CMD ["RESULTS", "18082", "work/preview-data/auth", "--container"]
