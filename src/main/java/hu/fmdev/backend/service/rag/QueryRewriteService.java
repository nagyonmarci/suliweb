package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.service.rag.RagSearchService.SearchFilters;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Query preprocessing for RAG search:
 * - HyDE (Hypothetical Document Embedding): generates a hypothetical answer, embeds that instead of the raw query
 * - Contextual rewrite: rewrites follow-up questions into standalone queries using conversation history
 */
@Service
public class QueryRewriteService {

    private final WebClient ollamaWebClient;
    private final RagConfig ragConfig;

    public QueryRewriteService(WebClient ollamaWebClient, RagConfig ragConfig) {
        this.ollamaWebClient = ollamaWebClient;
        this.ragConfig = ragConfig;
    }

    /**
     * HyDE: asks the LLM to generate a short hypothetical answer to the user's query.
     * The embedding of this hypothetical answer is closer to the actual documents than
     * the embedding of a short question.
     *
     * @return hypothetical answer text, or the original query if generation fails
     */
    public String generateHypotheticalAnswer(String query) {
        if (!ragConfig.isHydeEnabled()) {
            return query;
        }

        String prompt = """
                Te egy e-mail archívumban kereső rendszer vagy. A felhasználó az alábbi kérdést tette fel. \
                Írj egy rövid (3-5 mondatos) hipotetikus választ, mintha az egy e-mailben szerepelne. \
                NE írj bevezetőt, NE írd ki hogy "hipotetikus válasz", csak a tartalmat. \
                Magyarul válaszolj.

                Kérdés: """ + query;

        try {
            String answer = callOllamaGenerate(prompt);
            if (answer != null && !answer.isBlank()) {
                CentralLogger.logInfo("HyDE generated hypothetical answer for query: " + truncate(query, 60));
                return answer;
            }
        } catch (Exception e) {
            CentralLogger.logWarn("HyDE generation failed, falling back to original query: " + e.getMessage());
        }

        return query;
    }

    /**
     * Rewrites a follow-up question into a standalone query using conversation history.
     * E.g. "Mikor volt ez?" + history about "Kovács Péter szerződése" → "Mikor volt Kovács Péter szerződése?"
     *
     * @return standalone query, or the original query if rewriting fails or history is empty
     */
    public String rewriteWithHistory(String query, List<RagChatService.HistoryMessage> history) {
        if (history == null || history.isEmpty()) {
            return query;
        }

        // Build a condensed history summary (last 4 messages max)
        StringBuilder historyText = new StringBuilder();
        int start = Math.max(0, history.size() - 4);
        for (int i = start; i < history.size(); i++) {
            var msg = history.get(i);
            historyText.append(msg.role().equals("user") ? "Felhasználó" : "Asszisztens")
                    .append(": ")
                    .append(truncate(msg.content(), 200))
                    .append("\n");
        }

        String prompt = """
                Az alábbi beszélgetés alapján fogalmazd át az utolsó kérdést úgy, hogy önmagában is érthető legyen, \
                a korábbi kontextus nélkül is. Ha a kérdés már önálló, add vissza változatlanul. \
                Csak az átfogalmazott kérdést írd, semmi mást.

                Beszélgetés:
                """ + historyText + "\nÚj kérdés: " + query + "\n\nÖnálló kérdés:";

        try {
            String rewritten = callOllamaGenerate(prompt);
            if (rewritten != null && !rewritten.isBlank()) {
                CentralLogger.logInfo("Query rewritten: \"" + truncate(query, 40) + "\" → \"" + truncate(rewritten, 60) + "\"");
                return rewritten.trim();
            }
        } catch (Exception e) {
            CentralLogger.logWarn("Query rewrite failed, using original: " + e.getMessage());
        }

        return query;
    }

    /**
     * Extracts sender and date range from a natural language query using the LLM.
     * If the existing filters already contain sender/dates (set manually), they are preserved.
     * Returns the original filters unchanged on any parse or network failure.
     */
    public SearchFilters extractFilters(String query, SearchFilters existing) {
        boolean alreadyHasSender = existing != null && existing.sender() != null && !existing.sender().isBlank();
        boolean alreadyHasDates  = existing != null && (existing.startDate() != null || existing.endDate() != null);
        if (alreadyHasSender && alreadyHasDates) return existing;

        String prompt = """
                Extract metadata from this email search query.
                Reply ONLY with a single JSON object — no explanation, no markdown.
                Format: {"sender":"<name or email or null>","startDate":"<yyyy-MM-dd or null>","endDate":"<yyyy-MM-dd or null>"}
                Use null for any field not mentioned in the query.
                Query: """ + query;

        try {
            String raw = callOllamaGenerate(prompt);
            if (raw == null || raw.isBlank()) return existing;

            // Strip potential markdown code fences
            raw = raw.replaceAll("(?s)```[a-z]*\\n?|```", "").trim();
            int start = raw.indexOf('{');
            int end   = raw.lastIndexOf('}');
            if (start < 0 || end <= start) return existing;
            raw = raw.substring(start, end + 1);

            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = new ObjectMapper().readValue(raw, Map.class);

            String sender    = alreadyHasSender ? existing.sender()    : nullIfAbsent(parsed, "sender");
            String startDate = alreadyHasDates  ? existing.startDate() : nullIfAbsent(parsed, "startDate");
            String endDate   = alreadyHasDates  ? existing.endDate()   : nullIfAbsent(parsed, "endDate");
            String pstFile   = existing != null ? existing.pstFile()   : null;

            SearchFilters enriched = SearchFilters.of(sender, pstFile, startDate, endDate);
            if (enriched != SearchFilters.NONE) {
                CentralLogger.logInfo("Query filters extracted — sender: " + sender
                        + ", start: " + startDate + ", end: " + endDate);
            }
            return enriched;
        } catch (Exception e) {
            CentralLogger.logWarn("Filter extraction failed, using original filters: " + e.getMessage());
            return existing != null ? existing : SearchFilters.NONE;
        }
    }

    private String nullIfAbsent(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v == null || "null".equals(v) || String.valueOf(v).isBlank()) return null;
        return String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private String callOllamaGenerate(String prompt) {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ragConfig.getChatModel());
        requestBody.put("prompt", prompt);
        requestBody.put("stream", false);
        // Keep it short and focused
        requestBody.put("options", Map.of("num_predict", 150, "temperature", 0.3));

        Map<String, Object> response = ollamaWebClient.post()
                .uri("/api/generate")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(30))
                .block();

        if (response != null && response.containsKey("response")) {
            return String.valueOf(response.get("response"));
        }
        return null;
    }

    private String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
