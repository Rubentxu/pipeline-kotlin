FROM ${baseImage}
ENV IS_AGENT=true
RUN if ! java -version > /dev/null 2>&1; then echo "Error: JDK not found" && exit 1; fi
COPY app/ /app/
WORKDIR /app
CMD ["java", "-jar", "/usr/local/bin/pipeline-runner.jar", "-c", "config.yaml", "-s", "script.pipeline.kts"]
