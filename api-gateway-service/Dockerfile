FROM eclipse-temurin:25-jre-alpine
WORKDIR /workspace

RUN apk add --no-cache curl netcat-openbsd

ARG JAR_FILE
COPY ${JAR_FILE} gateway-app.jar

# Modern streamlined flag set for a Java 25 microservice
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -XX:+UseCompactObjectHeaders"

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -jar gateway-app.jar"]