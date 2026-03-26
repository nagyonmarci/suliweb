package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Orchestrates a RAG-grounded chat turn:
 * 1. Hybrid search for relevant chunks via RagSearchService
 * 2. Build a Hungarian-language system prompt with the retrieved context
 * 3. Call Ollama /api/chat (non-streaming) to generate an answer
 * 4. Return the answer + a deduplicated list of source emails
 */
@Service
public class RagChatService {

    private final RagSearchService searchService;
    private final WebClient ollamaWebClient;
    private final RagConfig ragConfig;

    public RagChatService(RagSearchService searchService,
                          WebClient ollamaWebClient,
                          RagConfig ragConfig) {
        this.searchService = searchService;
        this.ollamaWebClient = ollamaWebClient;
        this.ragConfig = ragConfig;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ChatResponse chat(String userMessage, int topK, String model) {
        int k = topK > 0 ? topK : ragConfig.getChatContextTopK();
        String resolvedModel = (model != null && !model.isBlank()) ? model : ragConfig.getChatModel();

        // 1. Retrieve relevant chunks
        List<RagSearchService.SearchResult> chunks = searchService.search(userMessage, k);
        List<ChatSource> sources = buildSources(chunks);

        // 2. Build context text
        String context = searchService.buildContext(userMessage, k);

        // 3. Build Ollama messages
        String systemPrompt = buildSystemPrompt(context);
        List<Map<String, String>> messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user",   "content", userMessage)
        );

        // 4. Call Ollama
        String answer = callOllama(messages, resolvedModel);
        return new ChatResponse(answer, sources);
    }

    // -------------------------------------------------------------------------
    // Ollama call
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private String callOllama(List<Map<String, String>> messages, String model) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", model);
        requestBody.put("messages", messages);
        requestBody.put("stream", false);

        try {
            Map<String, Object> response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (response == null) return "Nem érkezett válasz az LLM-től.";

            Map<String, Object> messageObj = (Map<String, Object>) response.get("message");
            if (messageObj == null) return "Az LLM válasza hiányos.";
            return String.valueOf(messageObj.getOrDefault("content", ""));

        } catch (Exception e) {
            CentralLogger.logError("Ollama chat call failed", e);
            throw new RuntimeException("Az LLM nem érhető el: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String buildSystemPrompt(String context) {
        return """
                Te egy e-mail archívum asszisztense vagy. \
                A felhasználó kérdéseire kizárólag az alábbi, a keresési rendszer által visszaadott e-mailek \
                tartalma alapján válaszolj. Ha a kontextusban nincs elegendő információ, jelezd ezt őszintén. \
                Válaszolj magyarul, pontosan és tömören.

                === Releváns e-mail részletek ===
                """ + context;
    }

    private List<ChatSource> buildSources(List<RagSearchService.SearchResult> chunks) {
        // Deduplicate by emailId, keep highest score per email
        Map<String, ChatSource> byEmail = new LinkedHashMap<>();
        for (RagSearchService.SearchResult r : chunks) {
            byEmail.merge(r.emailId(),
                    new ChatSource(r.emailId(), r.emailSubject(), r.senderName(), r.score()),
                    (a, b) -> a.score() >= b.score() ? a : b);
        }
        return byEmail.values().stream()
                .sorted(Comparator.comparingDouble(ChatSource::score).reversed())
                .collect(Collectors.toList());
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record ChatResponse(String answer, List<ChatSource> sources) {}

    public record ChatSource(String emailId, String subject, String sender, double score) {}

    public record ChatRequest(String message, int topK, String model) {
        public ChatRequest { if (topK <= 0) topK = 8; }
    }
}
