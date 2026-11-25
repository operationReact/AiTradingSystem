FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src ./src
# Maven Central intermittently answers HTTP 429 (Too Many Requests) to CI builds. Retry
# the same build with backoff so a rate-limit burst does not fail an otherwise valid image.
RUN set -eu; \
    for attempt in 1 2 3 4 5; do \
      if mvn --batch-mode --no-transfer-progress -DskipTests package; then break; fi; \
      if [ "$attempt" -eq 5 ]; then echo "maven build failed after 5 attempts" >&2; exit 1; fi; \
      echo "maven attempt $attempt failed; retrying after backoff" >&2; \
      sleep $((attempt * 30)); \
    done

FROM eclipse-temurin:21-jre-jammy
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.security.egd=file:/dev/./urandom"
WORKDIR /app
RUN useradd --system --uid 10001 --create-home appuser
COPY --from=build /workspace/target/*.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
