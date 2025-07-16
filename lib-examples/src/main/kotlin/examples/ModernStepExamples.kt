package examples

import dev.rubentxu.pipeline.steps.annotations.*

/**
 * Ejemplos modernos de funciones @Step que demuestran la inyección automática de pipelineContext.
 * 
 * IMPORTANTE: 
 * - Estas funciones se escriben SIN el parámetro pipelineContext
 * - El compiler plugin inyecta automáticamente pipelineContext como primer parámetro
 * - NO usar LocalPipelineContext.current - pipelineContext estará disponible directamente
 * 
 * Transformación automática:
 * 
 * Código escrito:
 * ```kotlin
 * @Step(name = "deployService")
 * suspend fun deployService(environment: String) {
 *     // pipelineContext estará disponible aquí automáticamente
 * }
 * ```
 * 
 * Código transformado por el compiler plugin:
 * ```kotlin
 * @Step(name = "deployService") 
 * suspend fun deployService(pipelineContext: PipelineContext, environment: String) {
 *     // pipelineContext está disponible directamente
 * }
 * ```
 */

/**
 * Ejemplo 1: Función simple sin parámetros
 * El compiler plugin inyectará pipelineContext automáticamente
 */
@Step(
    name = "checkSystemHealth",
    description = "Verifica el estado del sistema",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun checkSystemHealth() {
    // ✅ pipelineContext estará disponible automáticamente después de la transformación
    pipelineContext.logger.info("Verificando estado del sistema...")
    
    val memoryInfo = pipelineContext.executeShell("free -h")
    val diskInfo = pipelineContext.executeShell("df -h")
    val cpuInfo = pipelineContext.executeShell("top -bn1 | head -3")
    
    if (!memoryInfo.success || !diskInfo.success || !cpuInfo.success) {
        throw RuntimeException("Error al verificar estado del sistema")
    }
    
    pipelineContext.logger.info("Estado del sistema verificado correctamente")
}

/**
 * Ejemplo 2: Función con parámetros simples
 * El compiler plugin inyectará pipelineContext como PRIMER parámetro
 */
@Step(
    name = "deployToEnvironment",
    description = "Despliega aplicación a un ambiente específico",
    category = StepCategory.DEPLOY,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun deployToEnvironment(environment: String, version: String) {
    // ✅ pipelineContext estará disponible directamente
    pipelineContext.logger.info("Desplegando versión $version al ambiente: $environment")
    
    // Validar ambiente
    val validEnvironments = listOf("dev", "staging", "production")
    if (environment !in validEnvironments) {
        throw IllegalArgumentException("Ambiente inválido: $environment. Válidos: $validEnvironments")
    }
    
    // Ejecutar despliegue
    val deployCommand = "kubectl apply -f k8s/$environment/ --namespace=$environment"
    val result = pipelineContext.executeShell(deployCommand)
    
    if (!result.success) {
        throw RuntimeException("Fallo en el despliegue: ${result.stderr}")
    }
    
    pipelineContext.logger.info("Despliegue exitoso a $environment")
}

/**
 * Ejemplo 3: Función con parámetros complejos y configuración
 */
@Step(
    name = "runTestSuite",
    description = "Ejecuta suite de pruebas con configuración específica",
    category = StepCategory.TEST,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun runTestSuite(
    testType: String,
    parallel: Boolean = true,
    timeout: Int = 300,
    tags: List<String> = emptyList()
) {
    // ✅ pipelineContext inyectado automáticamente como primer parámetro
    pipelineContext.logger.info("Ejecutando suite de pruebas: $testType")
    
    // Construir comando con parámetros
    val parallelFlag = if (parallel) "--parallel" else ""
    val tagsFlag = if (tags.isNotEmpty()) "--tags=${tags.joinToString(",")}" else ""
    val timeoutFlag = "--timeout=${timeout}s"
    
    val testCommand = "./gradlew test --tests=*$testType* $parallelFlag $tagsFlag $timeoutFlag"
    
    pipelineContext.logger.info("Comando: $testCommand")
    
    val result = pipelineContext.executeShell(testCommand)
    
    if (!result.success) {
        // Usar contexto para archivo de reporte
        val reportPath = "build/reports/tests/test/index.html"
        if (pipelineContext.fileExists(reportPath)) {
            pipelineContext.logger.error("Ver reporte en: $reportPath")
        }
        throw RuntimeException("Falló la suite de pruebas $testType")
    }
    
    pipelineContext.logger.info("Suite de pruebas $testType completada exitosamente")
}

/**
 * Ejemplo 4: Función con manejo de archivos y variables de ambiente
 */
@Step(
    name = "backupDatabase",
    description = "Crea backup de la base de datos",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.TRUSTED // Backup requiere acceso privilegiado
)
suspend fun backupDatabase(databaseName: String, s3Bucket: String? = null) {
    // ✅ pipelineContext disponible automáticamente
    pipelineContext.logger.info("Iniciando backup de la base de datos: $databaseName")
    
    // Obtener configuración desde variables de ambiente
    val dbHost = pipelineContext.environment["DB_HOST"] 
        ?: throw IllegalArgumentException("DB_HOST no configurado")
    val dbUser = pipelineContext.environment["DB_USER"] 
        ?: throw IllegalArgumentException("DB_USER no configurado")
    val dbPassword = pipelineContext.environment["DB_PASSWORD"] 
        ?: throw IllegalArgumentException("DB_PASSWORD no configurado")
    
    // Crear nombre de archivo con timestamp
    val timestamp = System.currentTimeMillis()
    val backupFile = "${databaseName}_backup_$timestamp.sql"
    
    // Crear backup
    val backupCommand = "pg_dump -h $dbHost -U $dbUser -d $databaseName > $backupFile"
    pipelineContext.logger.info("Creando backup...")
    
    // Ejecutar con variables de ambiente
    val envVars = mapOf("PGPASSWORD" to dbPassword)
    val result = pipelineContext.executeShell(backupCommand, envVars)
    
    if (!result.success) {
        throw RuntimeException("Error creando backup: ${result.stderr}")
    }
    
    // Verificar que el archivo se creó
    if (!pipelineContext.fileExists(backupFile)) {
        throw RuntimeException("Archivo de backup no encontrado: $backupFile")
    }
    
    // Subir a S3 si se especifica bucket
    if (s3Bucket != null) {
        pipelineContext.logger.info("Subiendo backup a S3: $s3Bucket")
        val s3Command = "aws s3 cp $backupFile s3://$s3Bucket/backups/"
        val s3Result = pipelineContext.executeShell(s3Command)
        
        if (!s3Result.success) {
            pipelineContext.logger.warn("Error subiendo a S3, pero backup local exitoso: ${s3Result.stderr}")
        } else {
            pipelineContext.logger.info("Backup subido a S3 exitosamente")
            // Limpiar archivo local después de subir a S3
            pipelineContext.executeShell("rm $backupFile")
        }
    }
    
    pipelineContext.logger.info("Backup de $databaseName completado")
}

/**
 * Ejemplo 5: Función con retry logic y cache
 */
@Step(
    name = "fetchExternalData",
    description = "Obtiene datos de API externa con retry y cache",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun fetchExternalData(apiUrl: String, cacheKey: String? = null, maxRetries: Int = 3) {
    // ✅ pipelineContext inyectado automáticamente
    pipelineContext.logger.info("Obteniendo datos de: $apiUrl")
    
    // Verificar cache si se proporciona clave
    if (cacheKey != null) {
        val cachedData = pipelineContext.remember(cacheKey) { null }
        if (cachedData != null) {
            pipelineContext.logger.info("Datos encontrados en cache")
            return
        }
    }
    
    // Retry logic
    var attempt = 0
    var lastError: String = ""
    
    while (attempt < maxRetries) {
        attempt++
        pipelineContext.logger.info("Intento $attempt/$maxRetries...")
        
        val result = pipelineContext.executeShell("curl -f -s '$apiUrl'")
        
        if (result.success) {
            pipelineContext.logger.info("Datos obtenidos exitosamente")
            
            // Guardar en cache si se proporciona clave
            if (cacheKey != null) {
                pipelineContext.remember(cacheKey) { result.stdout }
                pipelineContext.logger.info("Datos guardados en cache")
            }
            
            // Guardar datos en archivo
            pipelineContext.writeFile("external-data.json", result.stdout)
            return
        }
        
        lastError = result.stderr
        if (attempt < maxRetries) {
            val delaySeconds = attempt * 2 // Backoff exponencial
            pipelineContext.logger.warn("Intento $attempt falló, reintentando en ${delaySeconds}s...")
            kotlinx.coroutines.delay(delaySeconds * 1000L)
        }
    }
    
    throw RuntimeException("Falló obteniendo datos después de $maxRetries intentos. Último error: $lastError")
}

/**
 * Ejemplo 6: Función de notificación con múltiples canales
 */
@Step(
    name = "sendNotification",
    description = "Envía notificación a múltiples canales",
    category = StepCategory.NOTIFICATION,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun sendNotification(
    message: String,
    channels: List<String> = listOf("slack"),
    urgent: Boolean = false
) {
    // ✅ pipelineContext estará disponible automáticamente
    pipelineContext.logger.info("Enviando notificación: $message")
    
    val urgentPrefix = if (urgent) "🚨 URGENTE: " else ""
    val fullMessage = "$urgentPrefix$message"
    
    for (channel in channels) {
        try {
            when (channel.lowercase()) {
                "slack" -> {
                    val webhookUrl = pipelineContext.environment["SLACK_WEBHOOK_URL"]
                        ?: throw IllegalArgumentException("SLACK_WEBHOOK_URL no configurado")
                    
                    val payload = """{"text": "$fullMessage"}"""
                    val result = pipelineContext.executeShell(
                        "curl -X POST -H 'Content-type: application/json' --data '$payload' '$webhookUrl'"
                    )
                    
                    if (result.success) {
                        pipelineContext.logger.info("✅ Notificación enviada a Slack")
                    } else {
                        pipelineContext.logger.error("❌ Error enviando a Slack: ${result.stderr}")
                    }
                }
                
                "email" -> {
                    val emailTo = pipelineContext.environment["EMAIL_TO"]
                        ?: throw IllegalArgumentException("EMAIL_TO no configurado")
                    
                    val emailCommand = "echo '$fullMessage' | mail -s 'Pipeline Notification' $emailTo"
                    val result = pipelineContext.executeShell(emailCommand)
                    
                    if (result.success) {
                        pipelineContext.logger.info("✅ Notificación enviada por email a $emailTo")
                    } else {
                        pipelineContext.logger.error("❌ Error enviando email: ${result.stderr}")
                    }
                }
                
                "teams" -> {
                    val teamsWebhook = pipelineContext.environment["TEAMS_WEBHOOK_URL"]
                        ?: throw IllegalArgumentException("TEAMS_WEBHOOK_URL no configurado")
                    
                    val teamsPayload = """{"text": "$fullMessage"}"""
                    val result = pipelineContext.executeShell(
                        "curl -X POST -H 'Content-type: application/json' --data '$teamsPayload' '$teamsWebhook'"
                    )
                    
                    if (result.success) {
                        pipelineContext.logger.info("✅ Notificación enviada a Teams")
                    } else {
                        pipelineContext.logger.error("❌ Error enviando a Teams: ${result.stderr}")
                    }
                }
                
                else -> {
                    pipelineContext.logger.warn("Canal de notificación no soportado: $channel")
                }
            }
        } catch (e: Exception) {
            pipelineContext.logger.error("Error enviando notificación a $channel: ${e.message}")
            if (urgent) {
                throw e // Fallar si es urgente y no se puede enviar
            }
        }
    }
    
    pipelineContext.logger.info("Notificaciones enviadas")
}

/**
 * Ejemplo 7: Función con validación de seguridad
 */
@Step(
    name = "validateSecurityCompliance",
    description = "Valida cumplimiento de políticas de seguridad",
    category = StepCategory.SECURITY,
    securityLevel = SecurityLevel.TRUSTED
)
suspend fun validateSecurityCompliance(projectPath: String = ".") {
    // ✅ pipelineContext inyectado automáticamente por el compiler plugin
    pipelineContext.logger.info("Validando cumplimiento de seguridad en: $projectPath")
    
    val securityChecks = mutableListOf<String>()
    var hasErrors = false
    
    // Check 1: Verificar que no hay secretos hardcodeados
    pipelineContext.logger.info("Verificando secretos hardcodeados...")
    val secretScanResult = pipelineContext.executeShell("grep -r -i 'password\\|secret\\|key' $projectPath/src || true")
    if (secretScanResult.stdout.isNotEmpty()) {
        securityChecks.add("⚠️ Posibles secretos hardcodeados encontrados")
        pipelineContext.logger.warn("Revisar: ${secretScanResult.stdout}")
    } else {
        securityChecks.add("✅ No se encontraron secretos hardcodeados")
    }
    
    // Check 2: Verificar dependencias vulnerables
    pipelineContext.logger.info("Escaneando vulnerabilidades en dependencias...")
    val vulnScanResult = pipelineContext.executeShell("./gradlew dependencyCheckAnalyze || true")
    if (!vulnScanResult.success) {
        securityChecks.add("❌ Error escaneando vulnerabilidades")
        hasErrors = true
    } else {
        securityChecks.add("✅ Escaneo de vulnerabilidades completado")
    }
    
    // Check 3: Verificar permisos de archivos
    pipelineContext.logger.info("Verificando permisos de archivos...")
    val permissionsResult = pipelineContext.executeShell("find $projectPath -type f -perm 777 | head -10")
    if (permissionsResult.stdout.isNotEmpty()) {
        securityChecks.add("⚠️ Archivos con permisos 777 encontrados")
        pipelineContext.logger.warn("Archivos con permisos 777: ${permissionsResult.stdout}")
    } else {
        securityChecks.add("✅ Permisos de archivos correctos")
    }
    
    // Check 4: Verificar configuración de HTTPS
    pipelineContext.logger.info("Verificando configuración HTTPS...")
    val httpsConfigResult = pipelineContext.executeShell("grep -r 'http://' $projectPath/src || true")
    if (httpsConfigResult.stdout.isNotEmpty()) {
        securityChecks.add("⚠️ URLs HTTP encontradas (usar HTTPS)")
        pipelineContext.logger.warn("URLs HTTP: ${httpsConfigResult.stdout}")
    } else {
        securityChecks.add("✅ Configuración HTTPS correcta")
    }
    
    // Generar reporte
    val reportContent = buildString {
        appendLine("# Reporte de Cumplimiento de Seguridad")
        appendLine()
        appendLine("Generado: ${java.time.Instant.now()}")
        appendLine("Proyecto: $projectPath")
        appendLine()
        appendLine("## Resultados:")
        securityChecks.forEach { appendLine("- $it") }
        appendLine()
        if (hasErrors) {
            appendLine("⚠️ Se encontraron problemas de seguridad que requieren atención")
        } else {
            appendLine("✅ Validación de seguridad completada exitosamente")
        }
    }
    
    pipelineContext.writeFile("security-compliance-report.md", reportContent)
    pipelineContext.logger.info("Reporte generado: security-compliance-report.md")
    
    if (hasErrors) {
        throw RuntimeException("Validación de seguridad falló - revisar reporte para detalles")
    }
    
    pipelineContext.logger.info("Cumplimiento de seguridad validado exitosamente")
}

/**
 * Ejemplo 8: Función que usa el contexto para coordinación entre steps
 */
@Step(
    name = "setupTestEnvironment",
    description = "Configura ambiente de pruebas compartido",
    category = StepCategory.UTIL,
    securityLevel = SecurityLevel.RESTRICTED
)
suspend fun setupTestEnvironment(environmentName: String) {
    // ✅ pipelineContext disponible automáticamente
    pipelineContext.logger.info("Configurando ambiente de pruebas: $environmentName")
    
    // Usar remember para compartir información entre steps
    val envConfig = pipelineContext.remember("test-env-$environmentName") {
        mapOf(
            "db_port" to (5432 + environmentName.hashCode() % 1000),
            "redis_port" to (6379 + environmentName.hashCode() % 1000),
            "app_port" to (8080 + environmentName.hashCode() % 1000),
            "setup_timestamp" to System.currentTimeMillis()
        )
    }
    
    pipelineContext.logger.info("Configuración del ambiente: $envConfig")
    
    // Configurar base de datos de pruebas
    val dbPort = envConfig["db_port"]
    val dbCommand = "docker run -d --name test-db-$environmentName -p $dbPort:5432 -e POSTGRES_DB=testdb postgres:13"
    val dbResult = pipelineContext.executeShell(dbCommand)
    
    if (!dbResult.success && !dbResult.stderr.contains("already in use")) {
        throw RuntimeException("Error configurando base de datos: ${dbResult.stderr}")
    }
    
    // Configurar Redis de pruebas
    val redisPort = envConfig["redis_port"]
    val redisCommand = "docker run -d --name test-redis-$environmentName -p $redisPort:6379 redis:6"
    val redisResult = pipelineContext.executeShell(redisCommand)
    
    if (!redisResult.success && !redisResult.stderr.contains("already in use")) {
        throw RuntimeException("Error configurando Redis: ${redisResult.stderr}")
    }
    
    // Esperar a que los servicios estén listos
    pipelineContext.logger.info("Esperando que los servicios estén listos...")
    kotlinx.coroutines.delay(5000)
    
    // Verificar conectividad
    val dbCheck = pipelineContext.executeShell("pg_isready -h localhost -p $dbPort")
    val redisCheck = pipelineContext.executeShell("redis-cli -h localhost -p $redisPort ping")
    
    if (!dbCheck.success || !redisCheck.success) {
        throw RuntimeException("Servicios no están listos después de la configuración")
    }
    
    // Guardar configuración para otros steps
    pipelineContext.environment["TEST_DB_URL"] = "postgresql://localhost:$dbPort/testdb"
    pipelineContext.environment["TEST_REDIS_URL"] = "redis://localhost:$redisPort"
    pipelineContext.environment["TEST_APP_PORT"] = envConfig["app_port"].toString()
    
    pipelineContext.logger.info("Ambiente de pruebas '$environmentName' configurado exitosamente")
}