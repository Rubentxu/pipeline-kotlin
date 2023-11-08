FROM ${baseImage}


RUN which java > /dev/null 2>&1
RUN if [ $? -ne 0 ]; then \\
      echo "Error No se encuentra JDK instalado en la imagen base"; \\
        exit 1; \\
    fi

COPY /app /app

CMD ["java", "-jar", "pipeline-cli.jar", "-c", "config.yaml", "-s", "script.pipeline.kts" ]
