package hu.fmdev.backend.service.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EntityExtractionServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatClient.ChatClientRequestSpec requestSpec;
    @Mock private ChatClient.CallResponseSpec callResponseSpec;

    private EntityExtractionService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        service = new EntityExtractionService(chatClientBuilder);
    }

    private void mockK1Response(String json) {
        when(callResponseSpec.content()).thenReturn(json);
    }

    @Test
    void extract_blankText_returnsEmptyWithoutCallingModel() {
        assertTrue(service.extract("").isEmpty());
        assertTrue(service.extract(null).isEmpty());
        verifyNoInteractions(chatClient);
    }

    @Test
    void extract_validK1Response_parsesEntities() {
        mockK1Response("{\"entities\":[{\"name\":\"John Smith\",\"type\":\"PERSON\"},"
                + "{\"name\":\"Microsoft\",\"type\":\"ORG\"}],"
                + "\"claims\":[],\"evidence\":[],\"mechanisms\":[],\"relations\":[]}");

        List<NerExtractor.ExtractedEntity> result = service.extract("Some business email text");

        assertEquals(2, result.size());
        assertEquals(new NerExtractor.ExtractedEntity("John Smith", "PERSON"), result.get(0));
        assertEquals(new NerExtractor.ExtractedEntity("Microsoft", "ORG"), result.get(1));
    }

    @Test
    void extract_emptyEntities_returnsEmptyList() {
        mockK1Response("{\"entities\":[],\"claims\":[],\"evidence\":[],\"mechanisms\":[],\"relations\":[]}");
        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_malformedJson_softFailsToEmptyList() {
        mockK1Response("this is not json at all {{{");
        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_modelThrows_softFailsToEmptyList() {
        when(callResponseSpec.content()).thenThrow(new RuntimeException("connection refused"));
        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_nullResponse_returnsEmptyList() {
        mockK1Response(null);
        assertTrue(service.extract("text").isEmpty());
    }

    @Test
    void extract_entityTooShort_filteredOut() {
        mockK1Response("{\"entities\":[{\"name\":\"AB\",\"type\":\"ORG\"},{\"name\":\"ABC\",\"type\":\"ORG\"}],"
                + "\"claims\":[],\"evidence\":[],\"mechanisms\":[],\"relations\":[]}");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(1, result.size());
        assertEquals("ABC", result.get(0).name());
    }

    @Test
    void extract_missingTypeDefaultsToTopic() {
        mockK1Response("{\"entities\":[{\"name\":\"Project Phoenix\"}],"
                + "\"claims\":[],\"evidence\":[],\"mechanisms\":[],\"relations\":[]}");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(1, result.size());
        assertEquals("TOPIC", result.get(0).type());
    }

    @Test
    void extractK1_fullResponse_parsesClaims() {
        mockK1Response("{\"entities\":[],"
                + "\"claims\":[{\"text\":\"Budget exceeded\",\"claimType\":\"FACTUAL\",\"confidence\":0.9}],"
                + "\"evidence\":[{\"text\":\"Q3 overrun\",\"evidenceType\":\"CITATION\",\"sourceRef\":\"q3\"}],"
                + "\"mechanisms\":[],\"relations\":[]}");

        K1ExtractionOutput output = service.extractK1("email text");

        assertEquals(1, output.claims().size());
        assertEquals("Budget exceeded", output.claims().get(0).getText());
        assertEquals(1, output.evidence().size());
    }

    @Test
    void extract_markdownFenceWrapped_stripsAndParses() {
        mockK1Response("```json\n{\"entities\":[{\"name\":\"Test Corp\",\"type\":\"ORG\"}],"
                + "\"claims\":[],\"evidence\":[],\"mechanisms\":[],\"relations\":[]}\n```");

        List<NerExtractor.ExtractedEntity> result = service.extract("text");

        assertEquals(1, result.size());
        assertEquals("Test Corp", result.get(0).name());
    }
}
