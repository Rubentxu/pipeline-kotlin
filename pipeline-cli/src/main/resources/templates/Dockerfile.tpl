FROM ${baseImage}


# Intenta ejecutar java -version para comprobar si Java está instalado y accesible
RUN if ! java -version > /dev/null 2>&1; then \
        echo "Error: No se encuentra JDK instalado en la imagen base" && exit 1; \
    fi

COPY /app /app

WORKDIR /app

CMD ["java", "-jar", "pipeline-cli.jar", "-c", "config.yaml", "-s", "script.pipeline.kts" ]
