package hu.fmdev.backend.service.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Extracts named entities from email text via Ollama.
 * Returns an empty list on any failure — graph ingestion continues without entities.
 */
@Service
public class EntityExtractionService implements NerExtractor {

    private static final int MAX_INPUT_CHARS = 3_000;

    private static final String NER_PROMPT = """
            Extract named entities from the following text. Return ONLY a JSON array, no explanation.
            Each element: {"name": "...", "type": "PERSON|ORG|TOPIC|LOCATION"}
            Include only clearly identifiable entities. Ignore common words.
            Text: """;

    private final WebClient ollamaWebClient;
    private final String nerModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EntityExtractionService(WebClient ollamaWebClient,
                                   @org.springframework.beans.factory.annotation.Value("${rag.ner-model:${rag.chat-model:llama3.2}}") String nerModel) {
        this.ollamaWebClient = ollamaWebClient;
        this.nerModel = nerModel;
    }

    public List<NerExtractor.ExtractedEntity> extract(String text) {
        if (text == null || text.isBlank()) return List.of();
        String truncated = text.length() > MAX_INPUT_CHARS ? text.substring(0, MAX_INPUT_CHARS) : text;
        try {
            Map<String, Object> requestBody = Map.of(
                    "model",  nerModel,
                    "prompt", NER_PROMPT + truncated,
                    "stream", false,
                    "format", "json");

            @SuppressWarnings("unchecked")
            Map<String, Object> response = ollamaWebClient.post()
                    .uri("/api/generate")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return List.of();
            String responseText = String.valueOf(response.getOrDefault("response", ""));
            return parseEntities(responseText);
        } catch (Exception e) {
            CentralLogger.logWarn("Entity extraction failed (soft fail): " + e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<NerExtractor.ExtractedEntity> parseEntities(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            // Response may be wrapped in a JSON object or be a bare array
            String trimmed = json.trim();
            if (trimmed.startsWith("{")) {
                // Try to find the array inside the object
                Map<String, Object> wrapper = objectMapper.readValue(trimmed, Map.class);
                for (Object v : wrapper.values()) {
                    if (v instanceof List<?> list) {
                        return toEntities((List<Object>) list);
                    }
                }
                return List.of();
            }
            List<Object> raw = objectMapper.readValue(trimmed, List.class);
            return toEntities(raw);
        } catch (Exception e) {
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<NerExtractor.ExtractedEntity> toEntities(List<Object> raw) {
        List<NerExtractor.ExtractedEntity> result = new ArrayList<>();
        for (Object item : raw) {
            if (!(item instanceof Map)) continue;
            Map<Object, Object> m = (Map<Object, Object>) item;
            String name = String.valueOf(m.getOrDefault("name", "")).trim();
            String type = String.valueOf(m.getOrDefault("type", "TOPIC")).trim();
            if (!name.isEmpty()) result.add(new NerExtractor.ExtractedEntity(name, type));
        }
        return result;
    }
}
