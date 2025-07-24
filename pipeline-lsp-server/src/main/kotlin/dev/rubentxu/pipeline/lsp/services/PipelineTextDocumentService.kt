package dev.rubentxu.pipeline.lsp.services

import dev.rubentxu.pipeline.lsp.analysis.PipelineAstAnalyzer
import dev.rubentxu.pipeline.lsp.analysis.StepCompletionProvider
import org.eclipse.lsp4j.*
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.TextDocumentService
import org.slf4j.LoggerFactory
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles all text document-related operations for Pipeline DSL files.
 * 
 * Responsibilities:
 * - Document lifecycle management (open/close/change)
 * - Syntax validation and error reporting
 * - Code completion for DSL constructs and @Step functions
 * - Hover information and documentation
 * - Document symbols for outline view
 */
class PipelineTextDocumentService : TextDocumentService {
    
    private val logger = LoggerFactory.getLogger(PipelineTextDocumentService::class.java)
    
    // Document management
    private val documents = ConcurrentHashMap<String, TextDocumentItem>()
    private lateinit var client: LanguageClient
    
    // Analysis components
    private val astAnalyzer = PipelineAstAnalyzer()
    private val completionProvider = StepCompletionProvider()
    
    fun connect(client: LanguageClient) {
        this.client = client
        logger.info("TextDocumentService connected to client")
    }
    
    fun shutdown() {
        documents.clear()
        logger.info("TextDocumentService shutdown complete")
    }
    
    override fun didOpen(params: DidOpenTextDocumentParams) {
        val doc = params.textDocument
        logger.info("Document opened: ${doc.uri}")
        
        // Store document
        documents[doc.uri] = TextDocumentItem().apply {
            uri = doc.uri
            languageId = doc.languageId
            version = doc.version
            text = doc.text
        }
        
        // Perform initial validation
        validateDocument(doc.uri, doc.text)
    }
    
    override fun didChange(params: DidChangeTextDocumentParams) {
        val doc = params.textDocument
        logger.debug("Document changed: ${doc.uri}")
        
        // Update document content
        val document = documents[doc.uri]
        if (document != null) {
            // For full sync mode, we replace the entire content
            val change = params.contentChanges.firstOrNull()
            if (change != null) {
                document.text = change.text
                document.version = doc.version
                
                // Re-validate document
                validateDocument(doc.uri, change.text)
            }
        }
    }
    
    override fun didClose(params: DidCloseTextDocumentParams) {
        val uri = params.textDocument.uri
        logger.info("Document closed: $uri")
        
        documents.remove(uri)
        // Clear diagnostics for closed document
        client.publishDiagnostics(PublishDiagnosticsParams(uri, emptyList()))
    }
    
    override fun didSave(params: DidSaveTextDocumentParams) {
        logger.debug("Document saved: ${params.textDocument.uri}")
        // Could trigger additional validation or processing here
    }
    
    override fun completion(params: CompletionParams): CompletableFuture<Either<List<CompletionItem>, CompletionList>> {
        val uri = params.textDocument.uri
        val position = params.position
        
        logger.debug("Completion requested for $uri at ${position.line}:${position.character}")
        
        val document = documents[uri]
        if (document == null) {
            logger.warn("Document not found for completion: $uri")
            return CompletableFuture.completedFuture(Either.forLeft(emptyList()))
        }
        
        return CompletableFuture.supplyAsync {
            try {
                val completions = completionProvider.getCompletions(document.text, position)
                Either.forLeft(completions)
            } catch (e: Exception) {
                logger.error("Error providing completions for $uri", e)
                Either.forLeft(emptyList())
            }
        }
    }
    
    override fun hover(params: HoverParams): CompletableFuture<Hover?> {
        val uri = params.textDocument.uri
        val position = params.position
        
        logger.debug("Hover requested for $uri at ${position.line}:${position.character}")
        
        val document = documents[uri]
        if (document == null) {
            return CompletableFuture.completedFuture(null)
        }
        
        return CompletableFuture.supplyAsync {
            try {
                // TODO: Implement hover information based on position
                // For now, return basic DSL information
                val content = MarkupContent().apply {
                    kind = MarkupKind.MARKDOWN
                    value = "**Pipeline DSL**\\n\\nPipeline configuration file"
                }
                
                Hover().apply {
                    contents = Either.forRight(content)
                }
            } catch (e: Exception) {
                logger.error("Error providing hover for $uri", e)
                null
            }
        }
    }
    
    override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> {
        val uri = params.textDocument.uri
        logger.debug("Document symbols requested for $uri")
        
        val document = documents[uri]
        if (document == null) {
            return CompletableFuture.completedFuture(emptyList())
        }
        
        return CompletableFuture.supplyAsync {
            try {
                astAnalyzer.extractDocumentSymbols(document.text, uri)
            } catch (e: Exception) {
                logger.error("Error extracting document symbols for $uri", e)
                emptyList()
            }
        }
    }
    
    /**
     * Validates a pipeline document and publishes diagnostics.
     */
    private fun validateDocument(uri: String, content: String) {
        try {
            logger.debug("Validating document: $uri")
            
            val diagnostics = astAnalyzer.validatePipelineScript(content, uri)
            
            // Publish diagnostics to client
            client.publishDiagnostics(PublishDiagnosticsParams(uri, diagnostics))
            
            logger.debug("Published ${diagnostics.size} diagnostics for $uri")
            
        } catch (e: Exception) {
            logger.error("Error validating document $uri", e)
            
            // Send error as diagnostic
            val errorDiagnostic = Diagnostic().apply {
                range = Range(Position(0, 0), Position(0, 0))
                severity = DiagnosticSeverity.Error
                message = "Validation error: ${e.message}"
                source = "pipeline-lsp"
            }
            
            client.publishDiagnostics(PublishDiagnosticsParams(uri, listOf(errorDiagnostic)))
        }
    }
    
    /**
     * Checks if a URI represents a pipeline script file.
     */
    private fun isPipelineScript(uri: String): Boolean {
        return try {
            val path = URI(uri).path
            path.endsWith(".pipeline.kts") || path.endsWith(".kts")
        } catch (e: Exception) {
            false
        }
    }
}