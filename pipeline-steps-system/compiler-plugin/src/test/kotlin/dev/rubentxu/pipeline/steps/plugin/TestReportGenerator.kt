package dev.rubentxu.pipeline.steps.plugin

import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.util.TraceClassVisitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Comprehensive test report generator for compiler plugin transformations.
 * 
 * Generates detailed HTML and text reports showing:
 * 1. Transformation summary with before/after comparisons
 * 2. IR tree differences (when available)
 * 3. Bytecode analysis with call site verification
 * 4. DSL extension generation summary
 * 5. Performance metrics and compilation statistics
 * 6. Visual diff representations
 * 
 * Reports are excluded from git via .gitignore to prevent repository bloat
 * while providing comprehensive debugging and verification information.
 */
class TestReportGenerator {
    
    data class TestRunReport(
        val timestamp: LocalDateTime = LocalDateTime.now(),
        val testSuiteName: String,
        val totalTests: Int,
        val passedTests: Int,
        val failedTests: Int,
        val transformationReports: List<TransformationReport> = emptyList(),
        val performanceMetrics: PerformanceMetrics = PerformanceMetrics(),
        val compilationDetails: CompilationDetails = CompilationDetails()
    ) {
        val successRate: Double get() = if (totalTests > 0) (passedTests.toDouble() / totalTests) * 100 else 0.0
        val formattedTimestamp: String get() = timestamp.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
    
    data class TransformationReport(
        val testName: String,
        val sourceCode: String,
        val originalAnalysis: BytecodeAnalyzer.ClassAnalysis?,
        val transformedAnalysis: BytecodeAnalyzer.ClassAnalysis?,
        val transformationSummary: String,
        val irAnalysis: IRAnalyzer.IRModuleAnalysis? = null,
        val dslGenerationSummary: String = "",
        val callSiteAnalysis: List<CallSiteDetail> = emptyList(),
        val bytecodeComparison: String = "",
        val success: Boolean = true,
        val errorDetails: String? = null
    )
    
    data class CallSiteDetail(
        val functionName: String,
        val originalSignature: String,
        val transformedSignature: String,
        val contextInjected: Boolean,
        val location: String
    )
    
    data class PerformanceMetrics(
        val totalCompilationTime: Long = 0L,
        val averageCompilationTime: Long = 0L,
        val memoryUsageKB: Long = 0L,
        val transformedFunctionCount: Int = 0,
        val generatedExtensionCount: Int = 0
    )
    
    data class CompilationDetails(
        val kotlinVersion: String = "2.2.0",
        val pluginVersion: String = "2.0-SNAPSHOT",
        val jvmTarget: String = "21",
        val pluginOptions: List<String> = emptyList(),
        val classpath: List<String> = emptyList()
    )
    
    /**
     * Generates a comprehensive test report in multiple formats
     */
    fun generateReport(testRunReport: TestRunReport, outputDir: File): ReportFiles {
        outputDir.mkdirs()
        
        val reportFiles = ReportFiles(
            htmlReport = File(outputDir, "test-report.html"),
            textReport = File(outputDir, "test-report.txt"),
            jsonReport = File(outputDir, "test-report.json"),
            transformationDetails = File(outputDir, "transformations-detailed.html")
        )
        
        generateHtmlReport(testRunReport, reportFiles.htmlReport)
        generateTextReport(testRunReport, reportFiles.textReport)
        generateJsonReport(testRunReport, reportFiles.jsonReport)
        generateDetailedTransformationReport(testRunReport, reportFiles.transformationDetails)
        
        // Create index file for easy navigation
        generateIndexFile(reportFiles, outputDir)
        
        println("📊 Comprehensive test reports generated:")
        println("   - HTML Report: ${reportFiles.htmlReport.absolutePath}")
        println("   - Text Report: ${reportFiles.textReport.absolutePath}")
        println("   - JSON Report: ${reportFiles.jsonReport.absolutePath}")
        println("   - Detailed Transformations: ${reportFiles.transformationDetails.absolutePath}")
        
        return reportFiles
    }
    
    private fun generateHtmlReport(testRunReport: TestRunReport, outputFile: File) {
        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang='en'>")
            appendLine("<head>")
            appendLine("    <meta charset='UTF-8'>")
            appendLine("    <meta name='viewport' content='width=device-width, initial-scale=1.0'>")
            appendLine("    <title>Pipeline Kotlin Compiler Plugin Test Report</title>")
            appendLine("    <style>")
            appendLine(getCssStyles())
            appendLine("    </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("    <div class='container'>")
            
            // Header
            appendLine("        <header class='header'>")
            appendLine("            <h1>🔧 Pipeline Kotlin Compiler Plugin Test Report</h1>")
            appendLine("            <p class='timestamp'>Generated: ${testRunReport.formattedTimestamp}</p>")
            appendLine("        </header>")
            
            // Summary
            appendLine("        <section class='summary'>")
            appendLine("            <h2>📊 Test Summary</h2>")
            appendLine("            <div class='metrics'>")
            appendLine("                <div class='metric success'>")
            appendLine("                    <h3>${testRunReport.passedTests}</h3>")
            appendLine("                    <p>Passed</p>")
            appendLine("                </div>")
            appendLine("                <div class='metric failure'>")
            appendLine("                    <h3>${testRunReport.failedTests}</h3>")
            appendLine("                    <p>Failed</p>")
            appendLine("                </div>")
            appendLine("                <div class='metric total'>")
            appendLine("                    <h3>${testRunReport.totalTests}</h3>")
            appendLine("                    <p>Total</p>")
            appendLine("                </div>")
            appendLine("                <div class='metric rate'>")
            appendLine("                    <h3>${String.format(java.util.Locale.US, "%.1f%%", testRunReport.successRate)}</h3>")
            appendLine("                    <p>Success Rate</p>")
            appendLine("                </div>")
            appendLine("            </div>")
            appendLine("        </section>")
            
            // Performance Metrics
            appendLine("        <section class='performance'>")
            appendLine("            <h2>⚡ Performance Metrics</h2>")
            appendLine("            <table class='performance-table'>")
            appendLine("                <tr><td>Total Compilation Time</td><td>${testRunReport.performanceMetrics.totalCompilationTime}ms</td></tr>")
            appendLine("                <tr><td>Average Compilation Time</td><td>${testRunReport.performanceMetrics.averageCompilationTime}ms</td></tr>")
            appendLine("                <tr><td>Memory Usage</td><td>${testRunReport.performanceMetrics.memoryUsageKB} KB</td></tr>")
            appendLine("                <tr><td>Transformed Functions</td><td>${testRunReport.performanceMetrics.transformedFunctionCount}</td></tr>")
            appendLine("                <tr><td>Generated Extensions</td><td>${testRunReport.performanceMetrics.generatedExtensionCount}</td></tr>")
            appendLine("            </table>")
            appendLine("        </section>")
            
            // Transformation Reports
            if (testRunReport.transformationReports.isNotEmpty()) {
                appendLine("        <section class='transformations'>")
                appendLine("            <h2>🔄 Transformation Reports</h2>")
                
                testRunReport.transformationReports.forEachIndexed { index, report ->
                    val statusClass = if (report.success) "success" else "failure"
                    val statusIcon = if (report.success) "✅" else "❌"
                    
                    appendLine("            <div class='transformation-item $statusClass'>")
                    appendLine("                <h3>$statusIcon ${report.testName}</h3>")
                    
                    if (!report.success && report.errorDetails != null) {
                        appendLine("                <div class='error-details'>")
                        appendLine("                    <h4>Error Details:</h4>")
                        appendLine("                    <pre>${report.errorDetails}</pre>")
                        appendLine("                </div>")
                    } else {
                        appendLine("                <div class='transformation-summary'>")
                        appendLine("                    <h4>Transformation Summary:</h4>")
                        appendLine("                    <pre>${report.transformationSummary}</pre>")
                        appendLine("                </div>")
                        
                        if (report.callSiteAnalysis.isNotEmpty()) {
                            appendLine("                <div class='call-sites'>")
                            appendLine("                    <h4>Call Site Analysis:</h4>")
                            appendLine("                    <ul>")
                            report.callSiteAnalysis.forEach { callSite ->
                                val contextIcon = if (callSite.contextInjected) "✅" else "❌"
                                appendLine("                        <li>$contextIcon <strong>${callSite.functionName}</strong>: ${callSite.originalSignature} → ${callSite.transformedSignature}</li>")
                            }
                            appendLine("                    </ul>")
                            appendLine("                </div>")
                        }
                        
                        if (report.dslGenerationSummary.isNotEmpty()) {
                            appendLine("                <div class='dsl-generation'>")
                            appendLine("                    <h4>DSL Generation:</h4>")
                            appendLine("                    <pre>${report.dslGenerationSummary}</pre>")
                            appendLine("                </div>")
                        }
                    }
                    
                    appendLine("            </div>")
                }
                
                appendLine("        </section>")
            }
            
            // Compilation Details
            appendLine("        <section class='compilation-details'>")
            appendLine("            <h2>🔧 Compilation Details</h2>")
            appendLine("            <table class='details-table'>")
            appendLine("                <tr><td>Kotlin Version</td><td>${testRunReport.compilationDetails.kotlinVersion}</td></tr>")
            appendLine("                <tr><td>Plugin Version</td><td>${testRunReport.compilationDetails.pluginVersion}</td></tr>")
            appendLine("                <tr><td>JVM Target</td><td>${testRunReport.compilationDetails.jvmTarget}</td></tr>")
            if (testRunReport.compilationDetails.pluginOptions.isNotEmpty()) {
                appendLine("                <tr><td>Plugin Options</td><td>${testRunReport.compilationDetails.pluginOptions.joinToString(", ")}</td></tr>")
            }
            appendLine("            </table>")
            appendLine("        </section>")
            
            appendLine("    </div>")
            appendLine("</body>")
            appendLine("</html>")
        }
        
        outputFile.writeText(html)
    }
    
    private fun generateTextReport(testRunReport: TestRunReport, outputFile: File) {
        val report = buildString {
            appendLine("=" + "=".repeat(79))
            appendLine("PIPELINE KOTLIN COMPILER PLUGIN TEST REPORT")
            appendLine("=" + "=".repeat(79))
            appendLine("Generated: ${testRunReport.formattedTimestamp}")
            appendLine("Test Suite: ${testRunReport.testSuiteName}")
            appendLine()
            
            appendLine("SUMMARY")
            appendLine("-".repeat(20))
            appendLine("Total Tests: ${testRunReport.totalTests}")
            appendLine("Passed: ${testRunReport.passedTests}")
            appendLine("Failed: ${testRunReport.failedTests}")
            appendLine("Success Rate: ${String.format(java.util.Locale.US, "%.1f%%", testRunReport.successRate)}")
            appendLine()
            
            appendLine("PERFORMANCE METRICS")
            appendLine("-".repeat(20))
            appendLine("Total Compilation Time: ${testRunReport.performanceMetrics.totalCompilationTime}ms")
            appendLine("Average Compilation Time: ${testRunReport.performanceMetrics.averageCompilationTime}ms")
            appendLine("Memory Usage: ${testRunReport.performanceMetrics.memoryUsageKB} KB")
            appendLine("Transformed Functions: ${testRunReport.performanceMetrics.transformedFunctionCount}")
            appendLine("Generated Extensions: ${testRunReport.performanceMetrics.generatedExtensionCount}")
            appendLine()
            
            if (testRunReport.transformationReports.isNotEmpty()) {
                appendLine("TRANSFORMATION REPORTS")
                appendLine("-".repeat(20))
                
                testRunReport.transformationReports.forEach { report ->
                    val status = if (report.success) "✅ PASSED" else "❌ FAILED"
                    appendLine("$status ${report.testName}")
                    appendLine()
                    
                    if (!report.success && report.errorDetails != null) {
                        appendLine("Error Details:")
                        appendLine(report.errorDetails)
                        appendLine()
                    } else {
                        appendLine("Transformation Summary:")
                        appendLine(report.transformationSummary)
                        appendLine()
                        
                        if (report.callSiteAnalysis.isNotEmpty()) {
                            appendLine("Call Site Analysis:")
                            report.callSiteAnalysis.forEach { callSite ->
                                val contextIcon = if (callSite.contextInjected) "✅" else "❌"
                                appendLine("  $contextIcon ${callSite.functionName}: ${callSite.originalSignature} → ${callSite.transformedSignature}")
                            }
                            appendLine()
                        }
                        
                        if (report.dslGenerationSummary.isNotEmpty()) {
                            appendLine("DSL Generation Summary:")
                            appendLine(report.dslGenerationSummary)
                            appendLine()
                        }
                    }
                    
                    appendLine("-".repeat(40))
                }
            }
            
            appendLine("COMPILATION DETAILS")
            appendLine("-".repeat(20))
            appendLine("Kotlin Version: ${testRunReport.compilationDetails.kotlinVersion}")
            appendLine("Plugin Version: ${testRunReport.compilationDetails.pluginVersion}")
            appendLine("JVM Target: ${testRunReport.compilationDetails.jvmTarget}")
            if (testRunReport.compilationDetails.pluginOptions.isNotEmpty()) {
                appendLine("Plugin Options: ${testRunReport.compilationDetails.pluginOptions.joinToString(", ")}")
            }
            appendLine()
            
            appendLine("=".repeat(80))
        }
        
        outputFile.writeText(report)
    }
    
    private fun generateJsonReport(testRunReport: TestRunReport, outputFile: File) {
        // Simple JSON generation - in a real implementation, use a proper JSON library
        val json = buildString {
            appendLine("{")
            appendLine("  \"timestamp\": \"${testRunReport.formattedTimestamp}\",")
            appendLine("  \"testSuiteName\": \"${testRunReport.testSuiteName}\",")
            appendLine("  \"totalTests\": ${testRunReport.totalTests},")
            appendLine("  \"passedTests\": ${testRunReport.passedTests},")
            appendLine("  \"failedTests\": ${testRunReport.failedTests},")
            appendLine("  \"successRate\": ${testRunReport.successRate},")
            appendLine("  \"performanceMetrics\": {")
            appendLine("    \"totalCompilationTime\": ${testRunReport.performanceMetrics.totalCompilationTime},")
            appendLine("    \"averageCompilationTime\": ${testRunReport.performanceMetrics.averageCompilationTime},")
            appendLine("    \"memoryUsageKB\": ${testRunReport.performanceMetrics.memoryUsageKB},")
            appendLine("    \"transformedFunctionCount\": ${testRunReport.performanceMetrics.transformedFunctionCount},")
            appendLine("    \"generatedExtensionCount\": ${testRunReport.performanceMetrics.generatedExtensionCount}")
            appendLine("  },")
            appendLine("  \"compilationDetails\": {")
            appendLine("    \"kotlinVersion\": \"${testRunReport.compilationDetails.kotlinVersion}\",")
            appendLine("    \"pluginVersion\": \"${testRunReport.compilationDetails.pluginVersion}\",")
            appendLine("    \"jvmTarget\": \"${testRunReport.compilationDetails.jvmTarget}\"")
            appendLine("  },")
            appendLine("  \"transformationReports\": [")
            testRunReport.transformationReports.forEachIndexed { index, report ->
                appendLine("    {")
                appendLine("      \"testName\": \"${report.testName}\",")
                appendLine("      \"success\": ${report.success},")
                appendLine("      \"transformationSummary\": \"${report.transformationSummary.replace("\"", "\\\"").replace("\n", "\\n")}\",")
                appendLine("      \"callSiteCount\": ${report.callSiteAnalysis.size}")
                append("    }")
                if (index < testRunReport.transformationReports.size - 1) appendLine(",")
                else appendLine()
            }
            appendLine("  ]")
            appendLine("}")
        }
        
        outputFile.writeText(json)
    }
    
    private fun generateDetailedTransformationReport(testRunReport: TestRunReport, outputFile: File) {
        val html = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang='en'>")
            appendLine("<head>")
            appendLine("    <meta charset='UTF-8'>")
            appendLine("    <title>Detailed Transformation Report</title>")
            appendLine("    <style>")
            appendLine(getCssStyles())
            appendLine("    </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("    <div class='container'>")
            appendLine("        <h1>🔍 Detailed Transformation Analysis</h1>")
            
            testRunReport.transformationReports.forEach { report ->
                appendLine("        <section class='detailed-transformation'>")
                appendLine("            <h2>${report.testName}</h2>")
                
                // Source Code
                appendLine("            <div class='source-code'>")
                appendLine("                <h3>📄 Source Code</h3>")
                appendLine("                <pre><code>${report.sourceCode}</code></pre>")
                appendLine("            </div>")
                
                // Bytecode Comparison
                if (report.bytecodeComparison.isNotEmpty()) {
                    appendLine("            <div class='bytecode-comparison'>")
                    appendLine("                <h3>🔄 Bytecode Comparison</h3>")
                    appendLine("                <pre><code>${report.bytecodeComparison}</code></pre>")
                    appendLine("            </div>")
                }
                
                // IR Analysis
                if (report.irAnalysis != null) {
                    appendLine("            <div class='ir-analysis'>")
                    appendLine("                <h3>🧠 IR Analysis</h3>")
                    appendLine("                <pre><code>${report.irAnalysis.generateReport()}</code></pre>")
                    appendLine("            </div>")
                }
                
                appendLine("        </section>")
            }
            
            appendLine("    </div>")
            appendLine("</body>")
            appendLine("</html>")
        }
        
        outputFile.writeText(html)
    }
    
    private fun generateIndexFile(reportFiles: ReportFiles, outputDir: File) {
        val indexHtml = buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html lang='en'>")
            appendLine("<head>")
            appendLine("    <meta charset='UTF-8'>")
            appendLine("    <title>Pipeline Kotlin Test Reports - Index</title>")
            appendLine("    <style>")
            appendLine(getCssStyles())
            appendLine("    </style>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("    <div class='container'>")
            appendLine("        <h1>📊 Pipeline Kotlin Test Reports</h1>")
            appendLine("        <p>Generated: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))}</p>")
            appendLine("        <div class='report-links'>")
            appendLine("            <a href='${reportFiles.htmlReport.name}' class='report-link'>📊 HTML Report</a>")
            appendLine("            <a href='${reportFiles.textReport.name}' class='report-link'>📄 Text Report</a>")
            appendLine("            <a href='${reportFiles.jsonReport.name}' class='report-link'>🔧 JSON Report</a>")
            appendLine("            <a href='${reportFiles.transformationDetails.name}' class='report-link'>🔍 Detailed Transformations</a>")
            appendLine("        </div>")
            appendLine("    </div>")
            appendLine("</body>")
            appendLine("</html>")
        }
        
        File(outputDir, "index.html").writeText(indexHtml)
    }
    
    private fun getCssStyles(): String = """
        body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; margin: 0; padding: 20px; background: #f5f5f5; line-height: 1.6; }
        .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        .header { text-align: center; border-bottom: 2px solid #e0e0e0; padding-bottom: 20px; margin-bottom: 30px; }
        h1 { color: #2c3e50; margin: 0; font-size: 2.5em; }
        h2 { color: #34495e; margin: 30px 0 15px 0; }
        h3 { color: #5a6c7d; margin: 20px 0 10px 0; }
        .timestamp { color: #666; margin: 10px 0 0 0; font-size: 1.1em; }
        .metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); gap: 20px; margin: 30px 0; }
        .metric { text-align: center; padding: 25px; border-radius: 12px; box-shadow: 0 4px 6px rgba(0,0,0,0.1); transition: transform 0.2s; }
        .metric:hover { transform: translateY(-2px); }
        .metric.success { background: linear-gradient(135deg, #d4edda, #c3e6cb); color: #155724; border: 1px solid #c3e6cb; }
        .metric.failure { background: linear-gradient(135deg, #f8d7da, #f5c6cb); color: #721c24; border: 1px solid #f5c6cb; }
        .metric.total { background: linear-gradient(135deg, #d1ecf1, #bee5eb); color: #0c5460; border: 1px solid #bee5eb; }
        .metric.rate { background: linear-gradient(135deg, #fff3cd, #ffeaa7); color: #856404; border: 1px solid #ffeaa7; }
        .metric h3 { margin: 0 0 8px 0; font-size: 2.5em; font-weight: bold; }
        .metric p { margin: 0; font-weight: 600; font-size: 1.1em; }
        table { width: 100%; border-collapse: collapse; margin: 20px 0; border-radius: 8px; overflow: hidden; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        th, td { padding: 15px; text-align: left; border-bottom: 1px solid #e9ecef; }
        th { background: linear-gradient(135deg, #f8f9fa, #e9ecef); font-weight: 700; color: #495057; }
        tr:nth-child(even) { background: #f8f9fa; }
        tr:hover { background: #e3f2fd; }
        .transformation-item { margin: 25px 0; padding: 25px; border-radius: 12px; border-left: 5px solid #ccc; box-shadow: 0 2px 8px rgba(0,0,0,0.08); transition: all 0.3s; }
        .transformation-item:hover { box-shadow: 0 4px 12px rgba(0,0,0,0.15); }
        .transformation-item.success { background: linear-gradient(135deg, #f8fff8, #e8f5e8); border-left-color: #28a745; }
        .transformation-item.failure { background: linear-gradient(135deg, #fff8f8, #ffe8e8); border-left-color: #dc3545; }
        .transformation-summary, .call-sites, .dsl-generation { margin: 20px 0; }
        .error-details { background: linear-gradient(135deg, #f8f8f8, #ececec); padding: 20px; border-radius: 8px; margin: 20px 0; border-left: 4px solid #ffc107; }
        pre { background: linear-gradient(135deg, #f8f9fa, #f1f3f4); padding: 20px; border-radius: 8px; overflow-x: auto; font-size: 0.95em; border: 1px solid #e9ecef; }
        code { background: #f8f9fa; padding: 2px 6px; border-radius: 4px; font-family: 'Monaco', 'Menlo', 'Ubuntu Mono', monospace; }
        .report-links { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 20px; margin: 40px 0; }
        .report-link { display: block; padding: 25px; text-align: center; background: linear-gradient(135deg, #007bff, #0056b3); color: white; text-decoration: none; border-radius: 12px; font-weight: 600; font-size: 1.1em; box-shadow: 0 4px 8px rgba(0,123,255,0.3); transition: all 0.3s; }
        .report-link:hover { background: linear-gradient(135deg, #0056b3, #004085); transform: translateY(-3px); box-shadow: 0 6px 12px rgba(0,123,255,0.4); }
        .source-code, .bytecode-comparison, .ir-analysis { margin: 30px 0; }
        .detailed-transformation { margin: 40px 0; padding: 30px; border: 1px solid #e9ecef; border-radius: 12px; background: linear-gradient(135deg, #ffffff, #f8f9fa); }
        ul { padding-left: 20px; }
        li { margin: 8px 0; }
        .performance-table tr:hover { background: #e8f4fd; }
        @media (max-width: 768px) {
            .container { padding: 20px; margin: 10px; }
            .metrics { grid-template-columns: 1fr; }
            .report-links { grid-template-columns: 1fr; }
            h1 { font-size: 2em; }
            .metric h3 { font-size: 2em; }
        }
    """
    
    /**
     * Creates a transformation report from bytecode analysis
     */
    fun createTransformationReport(
        testName: String,
        sourceCode: String,
        originalClassFile: File?,
        transformedClassFile: File?,
        success: Boolean = true,
        errorDetails: String? = null
    ): TransformationReport {
        val originalAnalysis = originalClassFile?.let { BytecodeAnalyzer().analyzeClassFile(it) }
        val transformedAnalysis = transformedClassFile?.let { BytecodeAnalyzer().analyzeClassFile(it) }
        
        val transformationSummary = if (originalAnalysis != null && transformedAnalysis != null) {
            BytecodeAnalyzer().compareTransformations(originalAnalysis, transformedAnalysis).getTransformationSummary()
        } else {
            "Unable to generate transformation summary"
        }
        
        val callSiteAnalysis = extractCallSiteAnalysis(transformedAnalysis)
        val bytecodeComparison = generateBytecodeComparison(originalClassFile, transformedClassFile)
        
        return TransformationReport(
            testName = testName,
            sourceCode = sourceCode,
            originalAnalysis = originalAnalysis,
            transformedAnalysis = transformedAnalysis,
            transformationSummary = transformationSummary,
            callSiteAnalysis = callSiteAnalysis,
            bytecodeComparison = bytecodeComparison,
            success = success,
            errorDetails = errorDetails
        )
    }
    
    private fun extractCallSiteAnalysis(classAnalysis: BytecodeAnalyzer.ClassAnalysis?): List<CallSiteDetail> {
        if (classAnalysis == null) return emptyList()
        
        return classAnalysis.methods.flatMap { method ->
            // Mock call site analysis - in real implementation, analyze method bytecode
            if (method.hasPipelineContextParameter()) {
                listOf(CallSiteDetail(
                    functionName = method.name,
                    originalSignature = "Original signature (mock)",
                    transformedSignature = method.descriptor,
                    contextInjected = true,
                    location = "Line unknown"
                ))
            } else {
                emptyList()
            }
        }
    }
    
    private fun generateBytecodeComparison(originalFile: File?, transformedFile: File?): String {
        if (originalFile == null || transformedFile == null) return "Bytecode comparison not available"
        
        return try {
            val originalBytecode = generateBytecodeDisassembly(originalFile)
            val transformedBytecode = generateBytecodeDisassembly(transformedFile)
            
            "ORIGINAL BYTECODE:\n$originalBytecode\n\nTRANSFORMED BYTECODE:\n$transformedBytecode"
        } catch (e: Exception) {
            "Error generating bytecode comparison: ${e.message}"
        }
    }
    
    private fun generateBytecodeDisassembly(classFile: File): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        val classReader = ClassReader(classFile.readBytes())
        val traceClassVisitor = TraceClassVisitor(printWriter)
        classReader.accept(traceClassVisitor, 0)
        return stringWriter.toString()
    }
    
    data class ReportFiles(
        val htmlReport: File,
        val textReport: File,
        val jsonReport: File,
        val transformationDetails: File
    )
}