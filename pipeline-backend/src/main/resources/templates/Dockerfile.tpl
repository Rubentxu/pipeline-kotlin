FROM ${baseImage}

ENV IS_AGENT=true

# Intenta ejecutar java -version para comprobar si Java está instalado y accesible
RUN if ! java -version > /dev/null 2>&1; then \
        echo "Error: No se encuentra JDK instalado en la imagen base" && exit 1; \
    fi

COPY /app /app

WORKDIR /app


<#-- Aquí se decide el comando a ejecutar -->
<#if executable == "pipeline-cli.jar">
CMD ["java", "-jar", "pipeline-cli.jar", "-c", "config.yaml", "-s", "script.pipeline.kts"]
<#else>
RUN chmod +x /app/pipeline-kts
CMD ["./${executable}", "-c", "config.yaml", "-s", "script.pipeline.kts"]
</#if>
