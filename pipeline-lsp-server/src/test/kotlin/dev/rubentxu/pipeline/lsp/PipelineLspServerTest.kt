package dev.rubentxu.pipeline.lsp

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.TextDocumentSyncKind
import org.eclipse.lsp4j.services.LanguageClient
import io.mockk.mockk

/**
 * Tests for the Pipeline LSP Server initialization and basic functionality.
 */
class PipelineLspServerTest : FunSpec({
    
    test("should initialize with correct capabilities") {
        // Given
        val server = PipelineLspServer()
        val mockClient = mockk<LanguageClient>(relaxed = true)
        val initParams = InitializeParams().apply {
            rootUri = "file:///test/project"
        }
        
        // When
        server.connect(mockClient)
        val result = server.initialize(initParams).get()
        
        // Then
        result shouldNotBe null
        result.capabilities shouldNotBe null
        
        with(result.capabilities) {
            // Check text document sync
            val syncKind = textDocumentSync.left
            syncKind shouldBe TextDocumentSyncKind.Full
            
            // Check completion support
            completionProvider shouldNotBe null
            completionProvider.triggerCharacters shouldBe listOf(".", "{", "(", " ")
            completionProvider.resolveProvider shouldBe true
            
            // Check workspace capabilities
            workspace shouldNotBe null
            workspace.workspaceFolders shouldNotBe null
            workspace.workspaceFolders.supported shouldBe true
        }
    }
    
    test("should provide text document service") {
        // Given
        val server = PipelineLspServer()
        
        // When
        val textDocumentService = server.textDocumentService
        
        // Then
        textDocumentService shouldNotBe null
    }
    
    test("should provide workspace service") {
        // Given
        val server = PipelineLspServer()
        
        // When
        val workspaceService = server.workspaceService
        
        // Then
        workspaceService shouldNotBe null
    }
    
    test("should shutdown gracefully") {
        // Given
        val server = PipelineLspServer()
        val mockClient = mockk<LanguageClient>(relaxed = true)
        val initParams = InitializeParams().apply {
            rootUri = "file:///test/project"
        }
        
        // Initialize first
        server.connect(mockClient)
        server.initialize(initParams).get()
        
        // When
        val shutdownResult = server.shutdown().get()
        
        // Then
        shutdownResult shouldBe null // Should complete successfully
    }
})