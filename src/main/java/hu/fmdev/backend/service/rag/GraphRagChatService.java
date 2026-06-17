package hu.fmdev.backend.service.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.domain.graph.EmailNode;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.service.AppSettingsService;
import hu.fmdev.backend.service.GraphSearchService;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.*;

/**
 * GraphRAG chat: extracts entities from the query → retrieves related EmailNodes from Neo4j
 * → builds a context prompt → calls Ollama for a grounded answer.
 */
@Service
public class GraphRagChatService {

    private final GraphSearchService graphSearch;
    private final EntityExtractionService entityExtraction;
    private final WebClient ollamaWebClient;
    private final AppSettingsService appSettingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GraphRagChatService(GraphSearchService graphSearch,
                               EntityExtractionService entityExtraction,
                               WebClient ollamaWebClient,
                               AppSettingsService appSettingsService) {
        this.graphSearch          = graphSearch;
        this.entityExtraction     = entityExtraction;
        this.ollamaWebClient      = ollamaWebClient;
        this.appSettingsService   = appSettingsService;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public ChatResponse chat(String userMessage, int topK, String model,
                             List<HistoryMessage> history) {
        int k = topK > 0 ? topK : appSettingsService.getEffectiveChatContextTopK();
        String resolvedModel = (model != null && !model.isBlank()) ? model : appSettingsService.getEffectiveChatModel();

        List<EmailNode> contextEmails = retrieveContext(userMessage, k);
        String context = buildContextText(contextEmails);
        List<ChatSource> sources = buildSources(contextEmails);

        List<Map<String, String>> messages = buildMessages(context, userMessage, history);
        String answer = callOllama(messages, resolvedModel);
        return new ChatResponse(answer, sources);
    }

    public ChatResponse chat(String userMessage, int topK, String model) {
        return chat(userMessage, topK, model, null);
    }

    public Flux<String> chatStream(String userMessage, int topK, String model,
                                   List<HistoryMessage> history) {
        int k = topK > 0 ? topK : appSettingsService.getEffectiveChatContextTopK();
        String resolvedModel = (model != null && !model.isBlank()) ? model : appSettingsService.getEffectiveChatModel();

        List<EmailNode> contextEmails = retrieveContext(userMessage, k);
        String context = buildContextText(contextEmails);
        List<ChatSource> sources = buildSources(contextEmails);
        List<Map<String, String>> messages = buildMessages(context, userMessage, history);

        String sourcesJson;
        try { sourcesJson = objectMapper.writeValueAsString(sources); }
        catch (JsonProcessingException e) { sourcesJson = "[]"; }
        final String finalSourcesJson = sourcesJson;

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model",    resolvedModel);
        requestBody.put("messages", messages);
        requestBody.put("stream",   true);

        return ollamaWebClient.post()
                .uri("/api/chat")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToFlux(Map.class)
                .mapNotNull(chunk -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = (Map<String, Object>) chunk.get("message");
                    if (msg != null) {
                        String content = String.valueOf(msg.getOrDefault("content", ""));
                        if (!content.isEmpty()) return "{\"token\":" + escapeJson(content) + "}";
                    }
                    return null;
                })
                .concatWith(Flux.just("{\"done\":true,\"sources\":" + finalSourcesJson + "}"))
                .onErrorResume(e -> {
                    CentralLogger.logError("GraphRAG streaming hiba", e);
                    return Flux.just("{\"error\":" + escapeJson(e.getMessage()) + "}");
                });
    }

    // -------------------------------------------------------------------------

    private List<EmailNode> retrieveContext(String query, int topK) {
        List<EntityExtractionService.ExtractedEntity> entities = entityExtraction.extract(query);
        if (entities.isEmpty()) return List.of();

        Set<String> seen = new LinkedHashSet<>();
        List<EmailNode> result = new ArrayList<>();
        for (EntityExtractionService.ExtractedEntity entity : entities) {
            for (EmailNode node : graphSearch.findEmailsByConceptProximity(entity.name(), topK)) {
                if (seen.add(node.getMongoId())) {
                    result.add(node);
                    if (result.size() >= topK) return result;
                }
            }
        }
        return result;
    }

    private String buildContextText(List<EmailNode> emails) {
        if (emails.isEmpty()) return "Nem található releváns tartalom a Knowledge Graph-ban.";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < emails.size(); i++) {
            EmailNode e = emails.get(i);
            sb.append("--- Találat ").append(i + 1).append(" ---\n")
              .append("Tárgy: ").append(e.getSubject()).append("\n")
              .append("Dátum: ").append(e.getDate()).append("\n")
              .append("PST: ").append(e.getPstFileName()).append("\n")
              .append("Tartalom: ").append(e.getBodyDelta() != null
                      ? e.getBodyDelta().substring(0, Math.min(500, e.getBodyDelta().length())) : "")
              .append("\n\n");
        }
        return sb.toString();
    }

    private List<ChatSource> buildSources(List<EmailNode> emails) {
        return emails.stream()
                .map(e -> new ChatSource(e.getMongoId(), e.getSubject(), e.getPstOwner()))
                .toList();
    }

    private List<Map<String, String>> buildMessages(String context, String userMessage,
                                                     List<HistoryMessage> history) {
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", buildSystemPrompt(context)));
        if (history != null) {
            int maxTurns = appSettingsService.getEffectiveChatMaxHistoryTurns() * 2;
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
        return messages;
    }

    private String buildSystemPrompt(String context) {
        return """
                Te egy e-mail archívum Knowledge Graph asszisztense vagy. \
                Az alábbi, gráf-alapú kereséssel talált e-mailrészletek alapján válaszolj. \
                Kizárólag az alábbi kontextusban szereplő információkra támaszkodj. \
                Ha nem találsz elegendő információt, mondd meg őszintén. \
                Válaszolj magyarul, tömören.

                === Kontextus ===
                """ + context;
    }

    @SuppressWarnings("unchecked")
    private String callOllama(List<Map<String, String>> messages, String model) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model",    model);
        requestBody.put("messages", messages);
        requestBody.put("stream",   false);
        try {
            Map<String, Object> response = ollamaWebClient.post()
                    .uri("/api/chat")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (response == null) return "Nem érkezett válasz az LLM-től.";
            Map<String, Object> msg = (Map<String, Object>) response.get("message");
            if (msg == null) return "Az LLM válasza hiányos.";
            return String.valueOf(msg.getOrDefault("content", ""));
        } catch (Exception e) {
            CentralLogger.logError("Ollama chat call failed", e);
            throw new RuntimeException("Az LLM nem érhető el: " + e.getMessage(), e);
        }
    }

    private String escapeJson(String s) {
        try { return objectMapper.writeValueAsString(s); }
        catch (JsonProcessingException e) { return "\"\""; }
    }

    // -------------------------------------------------------------------------
    // DTOs
    // -------------------------------------------------------------------------

    public record ChatResponse(String answer, List<ChatSource> sources) {}
    public record ChatSource(String emailId, String subject, String pstOwner) {}
    public record HistoryMessage(String role, String content) {}
    public record ChatRequest(String message, int topK, String model,
                              List<HistoryMessage> history) {}
}
