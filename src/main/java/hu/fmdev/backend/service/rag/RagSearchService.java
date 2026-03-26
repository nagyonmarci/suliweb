package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.EmailRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Hybrid semantic search: keyword candidates → cosine re-rank + pure semantic lane.
 * Two result lists are fused with Reciprocal Rank Fusion (RRF) for best accuracy.
 *
 * Architecture (Community MongoDB, no Atlas Vector Search):
 *  1. VECTOR lane  – embed query → load all chunks' embeddings → cosine similarity in JVM
 *                    Candidate pre-filtering: $text if available, else recent N chunks.
 *  2. KEYWORD lane – MongoDB $text full-text search on content/subject/sender
 *  3. RRF fusion   – merge the two ranked lists into one final ranking
 */
@Service
public class RagSearchService {

    private final EmbeddingService embeddingService;
    private final MongoTemplate mongoTemplate;
    private final EmailRepository emailRepository;
    private final RagConfig ragConfig;

    // RRF constant – higher = less sensitive to rank position
    private static final int RRF_K = 60;
    // Maximum chunks loaded into memory for cosine ranking
    private static final int VECTOR_CANDIDATE_LIMIT = 50_000;
    // Final results returned to caller (before email grouping)
    private static final int FINAL_TOP_K = 10;
    // Hard minimum cosine similarity for vector lane – cuts clear non-matches before RRF.
    // Intentionally low (config.searchMinScore is used as the final output gate instead).
    private static final double COSINE_PREFILTER = 0.20;
    // Weight of raw cosine score blended into the final RRF-based score (0 = pure RRF rank)
    private static final double COSINE_BLEND = 0.3;

    public RagSearchService(EmbeddingService embeddingService,
                            MongoTemplate mongoTemplate,
                            EmailRepository emailRepository,
                            RagConfig ragConfig) {
        this.embeddingService = embeddingService;
        this.mongoTemplate = mongoTemplate;
        this.emailRepository = emailRepository;
        this.ragConfig = ragConfig;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Hybrid search with optional PST file restriction.
     * Pass null for allowedPstFileNames to search all files.
     */
    public List<SearchResult> search(String query, int topK, Set<String> allowedPstFileNames) {
        int k = topK > 0 ? topK : FINAL_TOP_K;

        List<SearchResult> vectorResults  = vectorSearch(query, ragConfig.getSearchTopK(), allowedPstFileNames);
        List<SearchResult> keywordResults = keywordSearch(query, ragConfig.getSearchTopK(), allowedPstFileNames);

        return reciprocalRankFusion(vectorResults, keywordResults)
                .stream().limit(k).toList();
    }

    /** Overload without restriction – used internally and by admin paths. */
    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, null);
    }

    /** Semantic search grouped by email with top chunk per email. */
    public List<EmailSearchResult> searchEmails(String query, int topK, Set<String> allowedPstFileNames) {
        List<SearchResult> chunkResults = search(query, topK, allowedPstFileNames);

        Map<String, List<SearchResult>> grouped = chunkResults.stream()
                .collect(Collectors.groupingBy(SearchResult::emailId));

        List<EmailSearchResult> emailResults = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<SearchResult> chunks = entry.getValue();
            double bestScore = chunks.stream().mapToDouble(SearchResult::score).max().orElse(0);
            Email email = emailRepository.findById(entry.getKey()).orElse(null);
            if (email != null) {
                List<MatchedChunk> matchedChunks = chunks.stream()
                        .sorted(Comparator.comparingDouble(SearchResult::score).reversed())
                        .map(r -> new MatchedChunk(r.content(), r.sourceType(), r.attachmentFileName(), r.score()))
                        .toList();
                emailResults.add(new EmailSearchResult(email, bestScore, matchedChunks));
            }
        }

        emailResults.sort(Comparator.comparingDouble(EmailSearchResult::bestScore).reversed());
        return emailResults;
    }

    public List<EmailSearchResult> searchEmails(String query, int topK) {
        return searchEmails(query, topK, null);
    }

    /** Builds a human-readable context string for an LLM prompt. */
    public String buildContext(String query, int topK, Set<String> allowedPstFileNames) {
        List<SearchResult> results = search(query, topK > 0 ? topK : FINAL_TOP_K, allowedPstFileNames);
        if (results.isEmpty()) return "Nem található releváns tartalom.";

        StringBuilder sb = new StringBuilder("A keresés alapján talált releváns részletek:\n\n");
        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("--- Találat ").append(i + 1)
              .append(" (relevancia: ").append(String.format("%.2f", r.score())).append(") ---\n")
              .append("Email tárgy: ").append(r.emailSubject()).append("\n")
              .append("Feladó: ").append(r.senderName()).append("\n")
              .append("Forrás: ").append(r.sourceType());
            if (r.attachmentFileName() != null) sb.append(" (").append(r.attachmentFileName()).append(")");
            sb.append("\n").append("Tartalom: ").append(r.content()).append("\n\n");
        }
        return sb.toString();
    }

    public String buildContext(String query, int topK) {
        return buildContext(query, topK, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Vector search (cosine similarity in JVM – works on Community MongoDB)
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<SearchResult> vectorSearch(String query, int topK, Set<String> allowedPstFileNames) {
        List<Double> qVec = embeddingService.embed(query);
        if (qVec.isEmpty()) {
            CentralLogger.logWarn("Failed to embed query for vector search");
            return List.of();
        }

        double[] q = toDoubleArray(qVec);

        try {
            Document filter = new Document("ingestionStatus", "embedded");
            if (allowedPstFileNames != null) {
                filter.append("pstFileName", new Document("$in", new ArrayList<>(allowedPstFileNames)));
            }

            List<Document> candidates = mongoTemplate.getDb()
                    .getCollection("document_chunks")
                    .find(filter)
                    .projection(new Document("embedding", 1).append("emailId", 1)
                            .append("sourceType", 1).append("attachmentFileName", 1)
                            .append("content", 1).append("emailSubject", 1)
                            .append("senderName", 1).append("senderEmailAddress", 1)
                            .append("pstFileName", 1))
                    .limit(VECTOR_CANDIDATE_LIMIT)
                    .into(new ArrayList<>());

            List<SearchResult> scored = new ArrayList<>(candidates.size());
            for (Document doc : candidates) {
                List<Object> rawEmb = (List<Object>) doc.get("embedding");
                if (rawEmb == null || rawEmb.isEmpty()) continue;
                double[] dVec = toDoubleArrayFromObjects(rawEmb);
                if (dVec.length != q.length) continue;
                double sim = cosineSimilarity(q, dVec);
                if (sim >= COSINE_PREFILTER) {
                    scored.add(toSearchResultWithScore(doc, sim));
                }
            }

            scored.sort(Comparator.comparingDouble(SearchResult::score).reversed());
            return scored.stream().limit(topK).toList();
        } catch (Exception e) {
            CentralLogger.logError("Vector search (cosine) failed", e);
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyword search (MongoDB $text index)
    // ─────────────────────────────────────────────────────────────────────────

    private List<SearchResult> keywordSearch(String query, int topK, Set<String> allowedPstFileNames) {
        try {
            Document filter = new Document("$text", new Document("$search", query));
            if (allowedPstFileNames != null) {
                filter.append("pstFileName", new Document("$in", new ArrayList<>(allowedPstFileNames)));
            }

            List<Document> docs = mongoTemplate.getDb()
                    .getCollection("document_chunks")
                    .find(filter)
                    .projection(new Document("score", new Document("$meta", "textScore"))
                            .append("emailId", 1).append("sourceType", 1)
                            .append("attachmentFileName", 1).append("content", 1)
                            .append("emailSubject", 1).append("senderName", 1)
                            .append("senderEmailAddress", 1).append("pstFileName", 1))
                    .sort(new Document("score", new Document("$meta", "textScore")))
                    .limit(topK)
                    .into(new ArrayList<>());

            return docs.stream()
                    .map(doc -> {
                        double raw = doc.getDouble("score") != null ? doc.getDouble("score") : 0.0;
                        double norm = Math.log1p(raw) / Math.log1p(20.0);
                        return toSearchResultWithScore(doc, Math.min(norm, 1.0));
                    })
                    .toList();
        } catch (Exception e) {
            CentralLogger.logWarn("Keyword search failed (text index might be missing): " + e.getMessage());
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Reciprocal Rank Fusion
    // ─────────────────────────────────────────────────────────────────────────

    private List<SearchResult> reciprocalRankFusion(List<SearchResult> list1, List<SearchResult> list2) {
        Map<String, Double> rrfScores  = new LinkedHashMap<>();
        Map<String, Double> rawScores  = new LinkedHashMap<>();
        Map<String, SearchResult> bestResult = new LinkedHashMap<>();

        applyRrf(list1, rrfScores, rawScores, bestResult);
        applyRrf(list2, rrfScores, rawScores, bestResult);

        if (rrfScores.isEmpty()) return List.of();

        double rrfMax = (1.0 / (RRF_K + 1)) * 2;
        double minScore = ragConfig.getSearchMinScore();

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> {
                    SearchResult base = bestResult.get(e.getKey());
                    double rrfNorm  = Math.min(e.getValue() / rrfMax, 1.0);
                    double rawScore = rawScores.getOrDefault(e.getKey(), 0.0);
                    double blended = (1 - COSINE_BLEND) * rrfNorm + COSINE_BLEND * rawScore;
                    return new SearchResult(base.chunkId(), base.emailId(), base.sourceType(),
                            base.attachmentFileName(), base.content(), base.emailSubject(),
                            base.senderName(), base.senderEmailAddress(), base.pstFileName(), blended);
                })
                .filter(r -> r.score() >= minScore)
                .toList();
    }

    private void applyRrf(List<SearchResult> ranked, Map<String, Double> scores,
                          Map<String, Double> rawScores, Map<String, SearchResult> best) {
        for (int i = 0; i < ranked.size(); i++) {
            SearchResult r = ranked.get(i);
            String key = r.chunkId().isEmpty()
                    ? r.emailId() + "_" + Math.abs(r.content().hashCode())
                    : r.chunkId();
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            rawScores.merge(key, r.score(), Math::max);
            best.putIfAbsent(key, r);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Math helpers
    // ─────────────────────────────────────────────────────────────────────────

    private double cosineSimilarity(double[] a, double[] b) {
        if (a.length != b.length) return 0.0;
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot   += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom == 0.0 ? 0.0 : dot / denom;
    }

    private double[] toDoubleArray(List<Double> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    private double[] toDoubleArrayFromObjects(List<Object> list) {
        double[] arr = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Object o = list.get(i);
            arr[i] = o instanceof Number n ? n.doubleValue() : 0.0;
        }
        return arr;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mapping helpers
    // ─────────────────────────────────────────────────────────────────────────

    private SearchResult toSearchResultWithScore(Document doc, double score) {
        String id = "";
        if (doc.get("_id") instanceof org.bson.types.ObjectId oid) {
            id = oid.toHexString();
        } else if (doc.get("_id") != null) {
            id = doc.get("_id").toString();
        }
        return new SearchResult(id, doc.getString("emailId"), doc.getString("sourceType"),
                doc.getString("attachmentFileName"), doc.getString("content"),
                doc.getString("emailSubject"), doc.getString("senderName"),
                doc.getString("senderEmailAddress"), doc.getString("pstFileName"), score);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────────

    public record SearchResult(String chunkId, String emailId, String sourceType,
                               String attachmentFileName, String content,
                               String emailSubject, String senderName,
                               String senderEmailAddress, String pstFileName,
                               double score) {}

    public record MatchedChunk(String content, String sourceType,
                               String attachmentFileName, double score) {}

    public record EmailSearchResult(Email email, double bestScore,
                                    List<MatchedChunk> matchedChunks) {}
}
