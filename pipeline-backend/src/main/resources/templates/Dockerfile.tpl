FROM ${baseImage}

ENV IS_AGENT=true

# Verify that Java runtime is available in the base image
RUN if ! java -version > /dev/null 2>&1; then \
        echo "Error: No se encuentra JDK instalado en la imagen base" && exit 1; \
    fi

# Copy application files
COPY /app /app

WORKDIR /app

# The base image should include the pipeline runtime
# Simply execute the pipeline with the provided script and config
CMD ["java", "-jar", "pipeline-cli.jar", "-c", "config.yaml", "-s", "script.pipeline.kts"]
