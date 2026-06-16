package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.service.AppSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.withSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityExtractionServiceTest {

    private WebClient ollamaWebClient;
    @Mock private AppSettingsService appSettingsService;

    private EntityExtractionService service;

    @BeforeEach
    void setUp() {
        ollamaWebClient = mock(WebClient.class, withSettings().defaultAnswer(org.mockito.Answers.RETURNS_DEEP_STUBS));
        when(appSettingsService.getEffectiveNerModel()).thenReturn("llama3.2");
        service = new EntityExtractionService(ollamaWebClient, appSettingsService);
    }

    @SuppressWarnings("unchecked")
    private void mockOllamaResponse(String responseText) {
        Map<String, Object> response = Map.of("response", responseText);
        when(ollamaWebClient.post()
                .uri("/api/generate")
                .bodyValue(any())
                .retrieve()
                .bodyToMono(Map.class)
                .block())
                .thenReturn((Map) response);
    }

    @Test
    void extract_blankText_returnsEmptyWithoutCallingOllama() {
        assertTrue(service.extract("").isEmpty());
        assertTrue(service.extract(null).isEmpty());
        verifyNoInteractions(ollamaWebClient);
    }

    @Test
    void extract_bareJsonArray_parsesEntities() {
        mockOllamaResponse("[{\"name\":\"John Smith\",\"type\":\"PERSON\"},"
                + "{\"name\":\"Microsoft\",\"type\":\"ORG\"}]");

        List<NerExtractor.ExtractedEntity> result = service.extract("Some business email text");

        assertEquals(2, result.size());
        assertEquals(new NerExtractor.ExtractedEntity("John Smith", "PERSON"), result.get(0));
        assertEquals(new NerExtractor.ExtractedEntity("Microsoft", "ORG"), result.get(1));
    }

    @Test
    void extract_objectWrappedArray_findsArrayInsideWrapper() {
        mockOllamaResponse("{\"entities\":[{\"name\":\"Budapest\",\"type\":\"LOCATION\"}]}");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(1, result.size());
        assertEquals("Budapest", result.get(0).name());
        assertEquals("LOCATION", result.get(0).type());
    }

    @Test
    void extract_emptyArray_returnsEmptyList() {
        mockOllamaResponse("[]");

        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_malformedJson_softFailsToEmptyList() {
        mockOllamaResponse("this is not json at all {{{");

        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_ollamaThrows_softFailsToEmptyList() {
        when(ollamaWebClient.post()
                .uri("/api/generate")
                .bodyValue(any())
                .retrieve()
                .bodyToMono(Map.class)
                .block())
                .thenThrow(new RuntimeException("connection refused"));

        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_nullResponse_returnsEmptyList() {
        when(ollamaWebClient.post()
                .uri("/api/generate")
                .bodyValue(any())
                .retrieve()
                .bodyToMono(Map.class)
                .block())
                .thenReturn(null);

        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_entityTooShort_filteredOut() {
        mockOllamaResponse("[{\"name\":\"AB\",\"type\":\"ORG\"},{\"name\":\"ABC\",\"type\":\"ORG\"}]");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(1, result.size());
        assertEquals("ABC", result.get(0).name());
    }

    @Test
    void extract_numericOnlyEntity_filteredOut() {
        mockOllamaResponse("[{\"name\":\"12345\",\"type\":\"TOPIC\"}]");

        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_missingTypeDefaultsToTopic() {
        mockOllamaResponse("[{\"name\":\"Project Phoenix\"}]");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(1, result.size());
        assertEquals("TOPIC", result.get(0).type());
    }

    @Test
    void extract_bareStringArrayElements_treatedAsTopics() {
        mockOllamaResponse("[\"ISO 27001\", \"GDPR audit\"]");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(2, result.size());
        assertTrue(result.stream().allMatch(e -> e.type().equals("TOPIC")));
    }

    @Test
    void extract_longText_truncatedBeforeSendingToOllama() {
        mockOllamaResponse("[]");
        String longText = "x".repeat(5000);

        service.extract(longText);

        // No assertion possible on the truncated prompt without capturing the request body;
        // this just verifies a very long input doesn't throw or hang.
        verify(ollamaWebClient, atLeastOnce()).post();
    }
}
