# ===================================================================
# STAGE 1: Extract Layers inside an isolated build folder
# ===================================================================
FROM eclipse-temurin:25-jre-alpine AS extractor
WORKDIR /build

# Leverages a generic build-arg so you pass the jar name during compilation
ARG JAR_FILE
COPY ${JAR_FILE} app.jar

RUN java -Djarmode=layertools -jar app.jar extract

# ===================================================================
# STAGE 2: Compile the Minimal Production Runtime Image
# ===================================================================
FROM eclipse-temurin:25-jre-alpine
WORKDIR /workspace

RUN apk add --no-cache curl netcat-openbsd

COPY --from=extractor /build/dependencies/ ./
COPY --from=extractor /build/spring-boot-loader/ ./
COPY --from=extractor /build/snapshot-dependencies/ ./
COPY --from=extractor /build/application/ ./

# Modern streamlined flag set for a Java 25 microservice
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+UseCompactObjectHeaders"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} org.springframework.boot.loader.launch.JarLauncher"]