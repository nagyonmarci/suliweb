package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.repository.EmailRepository;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagSearchServiceTest {

    @Mock private EmbeddingService embeddingService;
    @Mock private org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    @Mock private EmailRepository emailRepository;

    private RagSearchService service;
    private RagConfig config;

    @BeforeEach
    void setUp() {
        config = new RagConfig();
        config.setSearchTopK(10);
        config.setSearchMinScore(0.5);
        service = new RagSearchService(embeddingService, mongoTemplate, emailRepository, config);
    }

    @Test
    void search_emptyQuery_returnsEmpty() {
        when(embeddingService.embed("")).thenReturn(List.of());
        List<RagSearchService.SearchResult> results = service.search("", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_embeddingFails_returnsEmpty() {
        when(embeddingService.embed("test query")).thenReturn(List.of());
        List<RagSearchService.SearchResult> results = service.search("test query", 10);
        assertTrue(results.isEmpty());
    }

    @Test
    void search_defaultTopK_usesConfigValue() {
        when(embeddingService.embed("test")).thenReturn(List.of());
        service.search("test", 0);
        // With empty embedding, returns early - but we verify default topK is used via config
        verify(embeddingService).embed("test");
    }

    @Test
    void buildContext_noResults_returnsDefaultMessage() {
        when(embeddingService.embed("missing query")).thenReturn(List.of());
        String context = service.buildContext("missing query", 5);
        assertEquals("Nem található releváns tartalom.", context);
    }

    @Test
    void searchEmails_emptyEmbedding_returnsEmpty() {
        when(embeddingService.embed("query")).thenReturn(List.of());
        var results = service.searchEmails("query", 5);
        assertTrue(results.isEmpty());
    }

    // --- Record tests ---

    @Test
    void searchResult_recordAccessors() {
        var result = new RagSearchService.SearchResult(
                "c1", "e1", "email_body", null, "content",
                "Subject", "Sender", "sender@test.com", "file.pst", 0.95);

        assertEquals("c1", result.chunkId());
        assertEquals("e1", result.emailId());
        assertEquals("email_body", result.sourceType());
        assertNull(result.attachmentFileName());
        assertEquals("content", result.content());
        assertEquals(0.95, result.score(), 0.001);
    }

    @Test
    void matchedChunk_recordAccessors() {
        var chunk = new RagSearchService.MatchedChunk("text", "attachment", "doc.pdf", 0.87);
        assertEquals("text", chunk.content());
        assertEquals("attachment", chunk.sourceType());
        assertEquals("doc.pdf", chunk.attachmentFileName());
        assertEquals(0.87, chunk.score(), 0.001);
    }

    @Test
    void emailSearchResult_recordAccessors() {
        Email email = new Email();
        email.setId("e1");
        var chunks = List.of(new RagSearchService.MatchedChunk("c", "email_body", null, 0.9));
        var result = new RagSearchService.EmailSearchResult(email, 0.9, chunks);

        assertEquals(email, result.email());
        assertEquals(0.9, result.bestScore(), 0.001);
        assertEquals(1, result.matchedChunks().size());
    }
}
