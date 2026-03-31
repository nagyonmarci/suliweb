package hu.fmdev.backend.controller;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.service.rag.EmbeddingService;
import hu.fmdev.backend.service.rag.RagChatService;
import hu.fmdev.backend.service.rag.RagIngestionService;
import hu.fmdev.backend.service.rag.RagSearchService;
import hu.fmdev.backend.repository.LogEntryRepository;
import hu.fmdev.backend.logger.CentralLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RagControllerTest {

    @Mock private RagIngestionService ingestionService;
    @Mock private RagSearchService searchService;
    @Mock private EmbeddingService embeddingService;
    @Mock private RagConfig ragConfig;
    @Mock private RagChatService chatService;
    @Mock private WebClient ollamaWebClient;
    @Mock private LogEntryRepository logEntryRepository;

    private RagController controller;

    @BeforeEach
    void setUp() {
        controller = new RagController(ingestionService, searchService, embeddingService, ragConfig, chatService, ollamaWebClient);

        // Initialize CentralLogger for static calls during tests
        CentralLogger logger = new CentralLogger();
        ReflectionTestUtils.setField(logger, "logEntryRepository", logEntryRepository);
        logger.init();
    }

    // --- /api/rag/ingest ---

    @Test
    void ingestAll_notRunning_startsIngestion() {
        when(ingestionService.isRunning()).thenReturn(false);

        var response = controller.ingestAll(false);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("elindítva"));
    }

    @Test
    void ingestAll_alreadyRunning_returnsBadRequest() {
        when(ingestionService.isRunning()).thenReturn(true);

        var response = controller.ingestAll(false);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertTrue(response.getBody().contains("folyamatban"));
    }

    // --- /api/rag/ingest/{emailId} ---

    @Test
    void reIngestEmail_returnsOk() {
        var response = controller.reIngestEmail("email123");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("email123"));
    }

    // --- /api/rag/embed ---

    @Test
    void embedPending_returnsOk() {
        var response = controller.embedPending();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertTrue(response.getBody().contains("Embedding"));
    }

    // --- /api/rag/search ---

    @Test
    void search_delegatesToService() {
        var expected = List.of(new RagSearchService.SearchResult(
                "c1", "e1", "email_body", null, "content",
                "Subject", "Sender", "s@t.com", "f.pst", 0.9));
        when(searchService.search(eq("test query"), eq(5), any())).thenReturn(expected);

        var result = controller.search("test query", 5, null, null, null, null);

        assertEquals(1, result.size());
        assertEquals("c1", result.getFirst().chunkId());
        verify(searchService).search(eq("test query"), eq(5), any());
    }

    @Test
    void search_emptyResults() {
        when(searchService.search(eq("nothing"), eq(10), any())).thenReturn(List.of());

        var result = controller.search("nothing", 10, null, null, null, null);
        assertTrue(result.isEmpty());
    }

    // --- /api/rag/search/emails ---

    @Test
    void searchEmails_delegatesToService() {
        when(searchService.searchEmails(eq("query"), eq(10), any())).thenReturn(List.of());

        var result = controller.searchEmails("query", 10, null, null, null, null);
        assertTrue(result.isEmpty());
        verify(searchService).searchEmails(eq("query"), eq(10), any());
    }

    // --- /api/rag/context ---

    @Test
    void getContext_returnsQueryAndContext() {
        when(searchService.buildContext("query", 5)).thenReturn("RAG context text");

        Map<String, String> result = controller.getContext("query", 5);

        assertEquals("query", result.get("query"));
        assertEquals("RAG context text", result.get("context"));
    }

    // --- /api/rag/stats ---

    @Test
    void getStats_delegatesToService() {
        var stats = new RagIngestionService.IngestionStats(100, 500, 450, 30, 20);
        when(ingestionService.getStats()).thenReturn(stats);

        var result = controller.getStats();

        assertEquals(100, result.totalEmails());
        assertEquals(500, result.totalChunks());
    }

    // --- /api/rag/health ---

    @Test
    void health_returnsStatusMap() {
        when(embeddingService.isAvailable()).thenReturn(true);
        when(ingestionService.isRunning()).thenReturn(false);
        when(ingestionService.getStats()).thenReturn(
                new RagIngestionService.IngestionStats(10, 50, 40, 5, 5));

        Map<String, Object> result = controller.health();

        assertEquals(true, result.get("ollamaAvailable"));
        assertEquals(false, result.get("ingestionRunning"));
        assertNotNull(result.get("stats"));
    }

    @Test
    void health_ollamaDown_reportsUnavailable() {
        when(embeddingService.isAvailable()).thenReturn(false);
        when(ingestionService.isRunning()).thenReturn(false);
        when(ingestionService.getStats()).thenReturn(
                new RagIngestionService.IngestionStats(0, 0, 0, 0, 0));

        Map<String, Object> result = controller.health();

        assertEquals(false, result.get("ollamaAvailable"));
    }
}
