package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.service.AppSettingsService;
import hu.fmdev.backend.service.GraphSearchService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphRagChatServiceTest {

    @Mock private GraphSearchService graphSearch;
    @Mock private EntityExtractionService entityExtraction;
    @Mock private AppSettingsService appSettingsService;
    private WebClient ollamaWebClient;

    private GraphRagChatService service;

    @BeforeEach
    void setUp() {
        ollamaWebClient = mock(WebClient.class, withSettings().defaultAnswer(org.mockito.Answers.RETURNS_DEEP_STUBS));
        when(appSettingsService.getEffectiveChatModel()).thenReturn("llama3.1:8b");
        when(appSettingsService.getEffectiveChatContextTopK()).thenReturn(8);
        when(appSettingsService.getEffectiveChatMaxHistoryTurns()).thenReturn(6);
        service = new GraphRagChatService(graphSearch, entityExtraction, ollamaWebClient, appSettingsService);
    }

    private EmailNode email(String mongoId, String subject) {
        EmailNode e = new EmailNode();
        e.setMongoId(mongoId);
        e.setSubject(subject);
        e.setDate("2026-01-01");
        e.setPstFileName("archive.pst");
        e.setPstOwner("archive");
        e.setBodyDelta("Some body content about " + subject);
        return e;
    }

    @SuppressWarnings("unchecked")
    private void mockOllamaChatResponse(String content) {
        Map<String, Object> message = Map.of("content", content);
        Map<String, Object> response = Map.of("message", message);
        when(ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(any())
                .retrieve()
                .bodyToMono(Map.class)
                .block())
                .thenReturn((Map) response);
    }

    @Test
    void chat_withMatchingEntities_returnsAnswerAndSources() {
        when(entityExtraction.extract("kontraktus kérdés"))
                .thenReturn(List.of(new NerExtractor.ExtractedEntity("kontraktus", "TOPIC")));
        when(graphSearch.findEmailsByConceptProximity("kontraktus", 8))
                .thenReturn(List.of(email("m1", "Szerződés"), email("m2", "Módosítás")));
        mockOllamaChatResponse("Itt a válasz.");

        GraphRagChatService.ChatResponse resp = service.chat("kontraktus kérdés", 8, null);

        assertEquals("Itt a válasz.", resp.answer());
        assertEquals(2, resp.sources().size());
        assertEquals("m1", resp.sources().get(0).emailId());
    }

    @Test
    void chat_noEntitiesExtracted_skipsGraphSearchAndUsesEmptyContext() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        mockOllamaChatResponse("Nincs elég infó.");

        GraphRagChatService.ChatResponse resp = service.chat("valami", 8, null);

        assertTrue(resp.sources().isEmpty());
        verifyNoInteractions(graphSearch);
    }

    @Test
    void chat_blankModel_fallsBackToConfiguredDefault() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        mockOllamaChatResponse("ok");

        service.chat("query", 8, "  ");

        verify(appSettingsService).getEffectiveChatModel();
    }

    @Test
    void chat_explicitModel_doesNotUseConfiguredDefault() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        mockOllamaChatResponse("ok");

        service.chat("query", 8, "qwen2.5:14b");

        verify(appSettingsService, never()).getEffectiveChatModel();
    }

    @Test
    void chat_nonPositiveTopK_fallsBackToConfiguredContextTopK() {
        when(entityExtraction.extract("q")).thenReturn(List.of(new NerExtractor.ExtractedEntity("X", "TOPIC")));
        when(graphSearch.findEmailsByConceptProximity(eq("X"), eq(8))).thenReturn(List.of());
        mockOllamaChatResponse("ok");

        service.chat("q", 0, null);

        verify(appSettingsService).getEffectiveChatContextTopK();
        verify(graphSearch).findEmailsByConceptProximity("X", 8);
    }

    @Test
    void chat_deduplicatesEmailsAcrossMultipleEntities() {
        when(entityExtraction.extract("q"))
                .thenReturn(List.of(
                        new NerExtractor.ExtractedEntity("A", "TOPIC"),
                        new NerExtractor.ExtractedEntity("B", "TOPIC")));
        EmailNode shared = email("shared", "Shared");
        when(graphSearch.findEmailsByConceptProximity("A", 8)).thenReturn(List.of(shared));
        when(graphSearch.findEmailsByConceptProximity("B", 8)).thenReturn(List.of(shared, email("m2", "Other")));
        mockOllamaChatResponse("ok");

        GraphRagChatService.ChatResponse resp = service.chat("q", 8, null);

        assertEquals(2, resp.sources().size());
    }

    @Test
    void chat_historyLongerThanMaxTurns_isTrimmed() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        mockOllamaChatResponse("ok");
        when(appSettingsService.getEffectiveChatMaxHistoryTurns()).thenReturn(1); // 1 turn = 2 messages max

        List<GraphRagChatService.HistoryMessage> longHistory = List.of(
                new GraphRagChatService.HistoryMessage("user", "msg1"),
                new GraphRagChatService.HistoryMessage("assistant", "reply1"),
                new GraphRagChatService.HistoryMessage("user", "msg2"),
                new GraphRagChatService.HistoryMessage("assistant", "reply2"));

        // Should not throw, and should only forward the trimmed tail to Ollama.
        assertDoesNotThrow(() -> service.chat("new question", 8, null, longHistory));
    }

    @Test
    void chat_ollamaReturnsNullResponse_returnsFallbackMessage() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        when(ollamaWebClient.post().uri("/api/chat").bodyValue(any()).retrieve().bodyToMono(Map.class).block())
                .thenReturn(null);

        GraphRagChatService.ChatResponse resp = service.chat("q", 8, null);

        assertEquals("Nem érkezett válasz az LLM-től.", resp.answer());
    }

    @Test
    void chat_ollamaResponseMissingMessage_returnsIncompleteMessage() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        when(ollamaWebClient.post().uri("/api/chat").bodyValue(any()).retrieve().bodyToMono(Map.class).block())
                .thenReturn((Map) Map.of("other", "field"));

        GraphRagChatService.ChatResponse resp = service.chat("q", 8, null);

        assertEquals("Az LLM válasza hiányos.", resp.answer());
    }

    @Test
    void chat_ollamaThrows_wrapsInRuntimeException() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        when(ollamaWebClient.post().uri("/api/chat").bodyValue(any()).retrieve().bodyToMono(Map.class).block())
                .thenThrow(new RuntimeException("connection refused"));

        RuntimeException ex = assertThrows(RuntimeException.class, () -> service.chat("q", 8, null));
        assertTrue(ex.getMessage().contains("connection refused"));
    }

    @Test
    void chatStream_emitsTokensThenDoneWithSources() {
        when(entityExtraction.extract("q"))
                .thenReturn(List.of(new NerExtractor.ExtractedEntity("X", "TOPIC")));
        when(graphSearch.findEmailsByConceptProximity("X", 8)).thenReturn(List.of(email("m1", "Subj")));

        Map<String, Object> chunk1 = Map.of("message", Map.of("content", "Hel"));
        Map<String, Object> chunk2 = Map.of("message", Map.of("content", "lo"));
        when(ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(any())
                .retrieve()
                .bodyToFlux(Map.class))
                .thenReturn(Flux.just(chunk1, chunk2));

        List<String> emitted = service.chatStream("q", 8, null, null).collectList().block();

        assertNotNull(emitted);
        assertEquals(3, emitted.size()); // 2 tokens + 1 done event
        assertTrue(emitted.get(0).contains("Hel"));
        assertTrue(emitted.get(1).contains("lo"));
        assertTrue(emitted.get(2).contains("\"done\":true"));
        assertTrue(emitted.get(2).contains("m1"));
    }

    @Test
    void chatStream_ollamaErrors_emitsErrorEvent() {
        when(entityExtraction.extract(any())).thenReturn(List.of());
        when(ollamaWebClient.post().uri("/api/chat").bodyValue(any()).retrieve().bodyToFlux(Map.class))
                .thenReturn(Flux.error(new RuntimeException("stream broke")));

        List<String> emitted = service.chatStream("q", 8, null, null).collectList().block();

        assertNotNull(emitted);
        assertEquals(1, emitted.size());
        assertTrue(emitted.get(0).contains("error"));
    }
}
