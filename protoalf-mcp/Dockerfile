# Built by docker compose after `mvn package` (see run.sh / run.bat): the context is this module folder.
FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 mcp
WORKDIR /app
COPY target/protoalf-mcp.jar app.jar
RUN mkdir /export && chown mcp /export
USER mcp

EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
