package dev.rubentxu.pipeline.lsp

import dev.rubentxu.pipeline.lsp.services.PipelineTextDocumentService
import dev.rubentxu.pipeline.lsp.services.PipelineWorkspaceService
import org.eclipse.lsp4j.CompletionOptions
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.ServerCapabilities
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageClientAware
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import org.slf4j.LoggerFactory
import java.util.concurrent.CompletableFuture

/**
 * Main Language Server Protocol server for Pipeline DSL files (.pipeline.kts).
 * 
 * Provides IDE support including:
 * - Syntax validation and error reporting
 * - Code completion for DSL blocks and @Step functions
 * - Document synchronization and management
 * - Workspace-wide pipeline project support
 */
class PipelineLspServer : LanguageServer, LanguageClientAware {
    
    private val logger = LoggerFactory.getLogger(PipelineLspServer::class.java)
    
    private lateinit var client: LanguageClient
    private val textDocumentService = PipelineTextDocumentService()
    private val workspaceService = PipelineWorkspaceService()
    
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> {
        logger.info("Initializing Pipeline LSP Server...")
        logger.info("Client: ${params.clientInfo?.name} ${params.clientInfo?.version}")
        logger.info("Root URI: ${params.rootUri}")
        
        val capabilities = ServerCapabilities().apply {
            // Text document synchronization
            textDocumentSync = Either.forLeft(TextDocumentSyncKind.Full)
            
            // Code completion support
            completionProvider = CompletionOptions().apply {
                triggerCharacters = listOf(".", "{", "(", " ")
                resolveProvider = true
            }
            
            // Hover support for documentation
            hoverProvider = Either.forLeft(true)
            
            // Document symbol provider for outline
            documentSymbolProvider = Either.forLeft(true)
            
            // Definition provider for navigation
            definitionProvider = Either.forLeft(true)
            
            // References provider
            referencesProvider = Either.forLeft(true)
            
            // Document formatting
            documentFormattingProvider = Either.forLeft(false) // TODO: Implement in Phase 2
            
            // Workspace support
            workspace = org.eclipse.lsp4j.WorkspaceServerCapabilities().apply {
                workspaceFolders = org.eclipse.lsp4j.WorkspaceFoldersOptions().apply {
                    supported = true
                    changeNotifications = Either.forRight<String, Boolean>(true)
                }
            }
        }
        
        // Initialize services with client reference
        textDocumentService.connect(client)
        workspaceService.connect(client)
        
        logger.info("Pipeline LSP Server initialized successfully")
        
        return CompletableFuture.completedFuture(
            InitializeResult(capabilities)
        )
    }
    
    override fun shutdown(): CompletableFuture<Any> {
        logger.info("Shutting down Pipeline LSP Server...")
        
        // Cleanup resources
        textDocumentService.shutdown()
        workspaceService.shutdown()
        
        logger.info("Pipeline LSP Server shutdown complete")
        return CompletableFuture.completedFuture(null)
    }
    
    override fun exit() {
        logger.info("Pipeline LSP Server exiting...")
        // Perform any final cleanup
        System.exit(0)
    }
    
    override fun getTextDocumentService(): TextDocumentService = textDocumentService
    
    override fun getWorkspaceService(): WorkspaceService = workspaceService
    
    override fun connect(client: LanguageClient) {
        this.client = client
        logger.info("Connected to language client: ${client.javaClass.simpleName}")
    }
}

/**
 * Main entry point for the Pipeline LSP Server.
 * 
 * Can be started in different modes:
 * - Standard mode: Communicates via stdin/stdout (default)
 * - Socket mode: Listens on a specific port (for debugging)
 */
fun main(args: Array<String>) {
    val logger = LoggerFactory.getLogger("PipelineLspServerMain")
    
    try {
        logger.info("Starting Pipeline LSP Server...")
        logger.info("Arguments: ${args.joinToString()}")
        
        val server = PipelineLspServer()
        val launcher = org.eclipse.lsp4j.launch.LSPLauncher.createServerLauncher(
            server,
            System.`in`,
            System.out
        )
        
        // Connect and start listening
        server.connect(launcher.remoteProxy)
        val listening = launcher.startListening()
        
        logger.info("Pipeline LSP Server is now listening for requests...")
        
        // Block until the server is shut down
        listening.get()
        
    } catch (e: Exception) {
        logger.error("Failed to start Pipeline LSP Server", e)
        System.exit(1)
    }
}