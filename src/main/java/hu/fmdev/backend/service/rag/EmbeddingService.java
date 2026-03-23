package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private final WebClient ollamaWebClient;
    private final RagConfig ragConfig;

    public EmbeddingService(WebClient ollamaWebClient, RagConfig ragConfig) {
        this.ollamaWebClient = ollamaWebClient;
        this.ragConfig = ragConfig;
    }

    /**
     * Generates an embedding vector for the given text using Ollama.
     */
    @SuppressWarnings("unchecked")
    public List<Double> embed(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        try {
            Map<String, Object> response = ollamaWebClient.post()
                    .uri("/api/embed")
                    .bodyValue(Map.of(
                            "model", ragConfig.getEmbeddingModel(),
                            "input", text))
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response != null && response.containsKey("embeddings")) {
                List<List<Double>> embeddings = (List<List<Double>>) response.get("embeddings");
                if (!embeddings.isEmpty()) {
                    return embeddings.getFirst();
                }
            }

            CentralLogger.logWarn("Empty embedding response from Ollama");
            return List.of();
        } catch (Exception e) {
            CentralLogger.logError("Ollama embedding call failed", e);
            return List.of();
        }
    }

    /**
     * Checks if the Ollama service is available and the embedding model is loaded.
     */
    public boolean isAvailable() {
        try {
            ollamaWebClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Pulls the embedding model if not already present.
     */
    public void ensureModelAvailable() {
        try {
            ollamaWebClient.post()
                    .uri("/api/pull")
                    .bodyValue(Map.of("name", ragConfig.getEmbeddingModel()))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();
            CentralLogger.logInfo("Ollama model pulled: " + ragConfig.getEmbeddingModel());
        } catch (Exception e) {
            CentralLogger.logError("Failed to pull Ollama model: " + ragConfig.getEmbeddingModel(), e);
        }
    }
}
