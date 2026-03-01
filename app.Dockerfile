FROM amazoncorretto:25-alpine

ENV LANGUAGE='en_US:en'

# Two layers: dependencies change rarely, app JAR changes often
COPY --chown=185 target/lib/ /deployments/lib/
COPY --chown=185 target/*.jar /deployments/
COPY --chown=185 config.toml /deployments/

EXPOSE 8181
HEALTHCHECK --interval=10s --timeout=3s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:8181/health/live || exit 1
USER 185

WORKDIR /deployments

ENTRYPOINT ["java", "--enable-preview", "-jar", "java-mcp-server-1.0.0.jar"]
