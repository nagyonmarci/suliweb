package hu.fmdev.backend.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

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
    private final QueryRewriteService queryRewriteService;
    private final WebClient ollamaWebClient;
    private final RagConfig ragConfig;

    public RagChatService(RagSearchService searchService,
                          QueryRewriteService queryRewriteService,
                          WebClient ollamaWebClient,
                          RagConfig ragConfig) {
        this.searchService = searchService;
        this.queryRewriteService = queryRewriteService;
        this.ollamaWebClient = ollamaWebClient;
        this.ragConfig = ragConfig;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ChatResponse chat(String userMessage, int topK, String model,
                              List<HistoryMessage> history) {
        int k = topK > 0 ? topK : ragConfig.getChatContextTopK();
        String resolvedModel = (model != null && !model.isBlank()) ? model : ragConfig.getChatModel();

        // 1. Rewrite follow-up questions into standalone queries using conversation history
        String searchQuery = queryRewriteService.rewriteWithHistory(userMessage, history);

        // 2. Retrieve relevant chunks using the (potentially rewritten) query
        List<RagSearchService.SearchResult> chunks = searchService.search(searchQuery, k);
        List<ChatSource> sources = buildSources(chunks);

        // 3. Build context text
        String context = searchService.buildContext(searchQuery, k);

        // 4. Build Ollama messages with conversation history
        String systemPrompt = buildSystemPrompt(context);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));

        // Include conversation history (limited to configured max turns)
        if (history != null && !history.isEmpty()) {
            int maxTurns = ragConfig.getChatMaxHistoryTurns() * 2; // each turn = user + assistant
            List<HistoryMessage> trimmed = history.size() > maxTurns
                    ? history.subList(history.size() - maxTurns, history.size())
                    : history;
            for (HistoryMessage msg : trimmed) {
                if (msg.role() != null && msg.content() != null) {
                    messages.add(Map.of("role", msg.role(), "content", msg.content()));
                }
            }
        }

        messages.add(Map.of("role", "user", "content", userMessage));

        // 5. Call Ollama
        String answer = callOllama(messages, resolvedModel);
        return new ChatResponse(answer, sources);
    }

    /** Backwards-compatible overload without history. */
    public ChatResponse chat(String userMessage, int topK, String model) {
        return chat(userMessage, topK, model, null);
    }

    /**
     * Streaming chat: returns a Flux of SSE data lines.
     * Each line is either {"token": "..."} or {"done": true, "sources": [...]}.
     */
    public Flux<String> chatStream(String userMessage, int topK, String model,
                                    List<HistoryMessage> history) {
        int k = topK > 0 ? topK : ragConfig.getChatContextTopK();
        String resolvedModel = (model != null && !model.isBlank()) ? model : ragConfig.getChatModel();

        // 1. Rewrite + search (blocking, before streaming starts)
        String searchQuery = queryRewriteService.rewriteWithHistory(userMessage, history);
        List<RagSearchService.SearchResult> chunks = searchService.search(searchQuery, k);
        List<ChatSource> sources = buildSources(chunks);
        String context = searchService.buildContext(searchQuery, k);

        // 2. Build messages
        String systemPrompt = buildSystemPrompt(context);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", systemPrompt));
        if (history != null && !history.isEmpty()) {
            int maxTurns = ragConfig.getChatMaxHistoryTurns() * 2;
            List<HistoryMessage> trimmed = history.size() > maxTurns
                    ? history.subList(history.size() - maxTurns, history.size())
                    : history;
            for (HistoryMessage msg : trimmed) {
                if (msg.role() != null && msg.content() != null) {
                    messages.add(Map.of("role", msg.role(), "content", msg.content()));
                }
            }
        }
        messages.add(Map.of("role", "user", "content", userMessage));

        // 3. Stream from Ollama
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", resolvedModel);
        requestBody.put("messages", messages);
        requestBody.put("stream", true);

        ObjectMapper mapper = new ObjectMapper();
        String sourcesJson;
        try {
            sourcesJson = mapper.writeValueAsString(sources);
        } catch (JsonProcessingException e) {
            sourcesJson = "[]";
        }
        final String finalSourcesJson = sourcesJson;

        return ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(Map.class)
                .mapNotNull(chunk -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> messageObj = (Map<String, Object>) chunk.get("message");
                    if (messageObj != null) {
                        String content = String.valueOf(messageObj.getOrDefault("content", ""));
                        if (!content.isEmpty()) {
                            return "{\"token\":" + escapeJsonString(content) + "}";
                        }
                    }
                    return null;
                })
                .concatWith(Flux.just("{\"done\":true,\"sources\":" + finalSourcesJson + "}"))
                .onErrorResume(e -> {
                    CentralLogger.logError("Streaming chat failed", e);
                    return Flux.just("{\"error\":" + escapeJsonString(e.getMessage()) + "}");
                });
    }

    private String escapeJsonString(String s) {
        if (s == null) return "null";
        try {
            return new ObjectMapper().writeValueAsString(s);
        } catch (JsonProcessingException e) {
            return "\"\"";
        }
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
                Te egy e-mail archívum intelligens asszisztense vagy. A felhasználó egy PST e-mail archívumban \
                keres információt, és te a keresési rendszer által visszaadott releváns e-mail részletek \
                alapján válaszolsz.

                ## Szabályok
                1. **Kizárólag** az alábbi kontextus alapján válaszolj. NE találj ki információt.
                2. Ha a kontextusban nincs elegendő információ, mondd el őszintén, és javasold a keresés pontosítását.
                3. Minden állításodnál hivatkozz a forrás e-mail tárgyára vagy feladójára (pl. "A «Szerződésmódosítás» tárgyú levélben...").
                4. Ha több, egymásnak ellentmondó információt találsz, jelezd a különbségeket.
                5. Válaszolj magyarul, tömören és strukturáltan (használj felsorolást, ha több elemet említesz).
                6. Ha dátumot említesz, mindig add meg pontosan, ahogy az az e-mailben szerepel.
                7. Ez egy archívum – a benne lévő adatok nem feltétlenül aktuálisak.

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

    public record HistoryMessage(String role, String content) {}

    public record ChatRequest(String message, int topK, String model,
                               List<HistoryMessage> history) {
        public ChatRequest { if (topK <= 0) topK = 8; }
    }
}
