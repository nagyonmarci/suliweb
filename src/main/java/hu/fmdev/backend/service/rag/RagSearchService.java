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
    private final QueryRewriteService queryRewriteService;
    private final QdrantService qdrantService;
    private final MongoTemplate mongoTemplate;
    private final EmailRepository emailRepository;
    private final RagConfig ragConfig;

    // RRF constant – higher = less sensitive to rank position
    private static final int RRF_K = 60;
    // Maximum chunks loaded into memory for cosine ranking (fallback when Qdrant is unavailable)
    private static final int VECTOR_CANDIDATE_LIMIT = 50_000;
    // Final results returned to caller (before email grouping)
    private static final int FINAL_TOP_K = 10;
    // Hard minimum cosine similarity for vector lane – cuts clear non-matches before RRF.
    private static final double COSINE_PREFILTER = 0.20;
    // Weight of raw cosine score blended into the final RRF-based score (0 = pure RRF rank)
    private static final double COSINE_BLEND = 0.3;

    public RagSearchService(EmbeddingService embeddingService,
                            QueryRewriteService queryRewriteService,
                            QdrantService qdrantService,
                            MongoTemplate mongoTemplate,
                            EmailRepository emailRepository,
                            RagConfig ragConfig) {
        this.embeddingService = embeddingService;
        this.queryRewriteService = queryRewriteService;
        this.qdrantService = qdrantService;
        this.mongoTemplate = mongoTemplate;
        this.emailRepository = emailRepository;
        this.ragConfig = ragConfig;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Search without filters (backwards-compatible). */
    public List<SearchResult> search(String query, int topK) {
        return search(query, topK, SearchFilters.NONE);
    }

    /**
     * Hybrid search with optional metadata filters.
     * Uses HyDE (Hypothetical Document Embedding) for the vector lane when enabled.
     */
    public List<SearchResult> search(String query, int topK, SearchFilters filters) {
        int k = topK > 0 ? topK : FINAL_TOP_K;

        // HyDE: generate a hypothetical answer and use its embedding for vector search
        String vectorQuery = queryRewriteService.generateHypotheticalAnswer(query);

        List<SearchResult> vectorResults  = vectorSearch(vectorQuery, ragConfig.getSearchTopK());
        // Keyword lane always uses the original query for exact term matching
        List<SearchResult> keywordResults = keywordSearch(query, ragConfig.getSearchTopK());

        List<SearchResult> fused = reciprocalRankFusion(vectorResults, keywordResults);

        // Apply post-search metadata filters
        if (filters.hasAny()) {
            fused = fused.stream().filter(r -> filters.matches(r)).toList();
        }

        return fused.stream().limit(k).toList();
    }

    /** Semantic search grouped by email with top chunk per email (backwards-compatible). */
    public List<EmailSearchResult> searchEmails(String query, int topK) {
        return searchEmails(query, topK, SearchFilters.NONE);
    }

    /** Semantic search grouped by email with optional metadata filters. */
    public List<EmailSearchResult> searchEmails(String query, int topK, SearchFilters filters) {
        List<SearchResult> chunkResults = search(query, topK > 0 ? topK * 3 : FINAL_TOP_K * 3, filters);

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

    /** Builds a human-readable context string for an LLM prompt. */
    public String buildContext(String query, int topK, java.util.Set<String> allowedPstFileNames) {
        var filters = allowedPstFileNames != null
                ? SearchFilters.of(null, null, null, null, allowedPstFileNames)
                : SearchFilters.NONE;
        List<SearchResult> results = search(query, topK > 0 ? topK : FINAL_TOP_K, filters);
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
    // Vector search – delegates to Qdrant (HNSW) when available, falls back
    // to brute-force cosine similarity in JVM for Community MongoDB setups
    // ─────────────────────────────────────────────────────────────────────────

    private List<SearchResult> vectorSearch(String query, int topK) {
        List<Double> qVec = embeddingService.embed(query);
        if (qVec.isEmpty()) {
            CentralLogger.logWarn("Failed to embed query for vector search");
            return List.of();
        }

        // Prefer Qdrant ANN search when available (millisecond latency vs seconds for brute-force)
        if (qdrantService.isAvailable()) {
            return qdrantVectorSearch(qVec, topK);
        }
        return bruteForceVectorSearch(qVec, topK);
    }

    /**
     * Fast ANN search via Qdrant HNSW index.
     */
    private List<SearchResult> qdrantVectorSearch(List<Double> qVec, int topK) {
        try {
            List<QdrantService.QdrantHit> hits = qdrantService.search(qVec, topK);
            return hits.stream()
                    .filter(hit -> hit.score() >= COSINE_PREFILTER)
                    .map(hit -> new SearchResult(
                            hit.payload().getOrDefault("chunkId", hit.pointId()),
                            hit.payload().getOrDefault("emailId", ""),
                            hit.payload().getOrDefault("sourceType", ""),
                            hit.payload().get("attachmentFileName"),
                            hit.payload().getOrDefault("content", ""),
                            hit.payload().getOrDefault("emailSubject", ""),
                            hit.payload().getOrDefault("senderName", ""),
                            hit.payload().getOrDefault("senderEmailAddress", ""),
                            hit.payload().getOrDefault("pstFileName", ""),
                            hit.score()))
                    .toList();
        } catch (Exception e) {
            CentralLogger.logError("Qdrant vector search failed, falling back to brute-force", e);
            return bruteForceVectorSearch(qVec, topK);
        }
    }

    /**
     * Brute-force cosine similarity in JVM – fallback when Qdrant is not available.
     */
    @SuppressWarnings("unchecked")
    private List<SearchResult> bruteForceVectorSearch(List<Double> qVec, int topK) {
        double[] q = toDoubleArray(qVec);

        try {
            List<Document> candidates = mongoTemplate.getDb()
                    .getCollection("document_chunks")
                    .find(new Document("ingestionStatus", "embedded"))
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
            CentralLogger.logError("Vector search (brute-force cosine) failed", e);
            return List.of();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Keyword search (MongoDB $text index)
    // ─────────────────────────────────────────────────────────────────────────

    private List<SearchResult> keywordSearch(String query, int topK) {
        try {
            List<Document> docs = mongoTemplate.getDb()
                    .getCollection("document_chunks")
                    .find(new Document("$text", new Document("$search", query)))
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
                        // ln(1 + textScore) gives a stable 0-based score without a fixed ceiling
                        double raw = doc.getDouble("score") != null ? doc.getDouble("score") : 0.0;
                        double norm = Math.log1p(raw) / Math.log1p(20.0); // ln(1+20)≈3.04 as soft ceiling
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
        Map<String, Double> rawScores  = new LinkedHashMap<>();  // best raw cosine/keyword score per chunk
        Map<String, SearchResult> bestResult = new LinkedHashMap<>();

        applyRrf(list1, rrfScores, rawScores, bestResult);
        applyRrf(list2, rrfScores, rawScores, bestResult);

        if (rrfScores.isEmpty()) return List.of();

        // Normalise RRF scores to [0,1] relative to the theoretical max (two lists, rank 0 in each)
        double rrfMax = (1.0 / (RRF_K + 1)) * 2;

        double minScore = ragConfig.getSearchMinScore();

        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> {
                    SearchResult base = bestResult.get(e.getKey());
                    double rrfNorm  = Math.min(e.getValue() / rrfMax, 1.0);
                    double rawScore = rawScores.getOrDefault(e.getKey(), 0.0);
                    // Blend: RRF rank tells us relative position; raw score preserves absolute signal
                    double blended = (1 - COSINE_BLEND) * rrfNorm + COSINE_BLEND * rawScore;
                    return new SearchResult(base.chunkId(), base.emailId(), base.sourceType(),
                            base.attachmentFileName(), base.content(), base.emailSubject(),
                            base.senderName(), base.senderEmailAddress(), base.pstFileName(), blended);
                })
                // Apply final minimum-score gate here (after blending, not before)
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
            // Keep the highest raw score seen for this chunk across both lanes
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

    /**
     * Metadata filters for post-search filtering.
     * All fields are optional – null or blank means "don't filter".
     */
    public record SearchFilters(String sender, String pstFile, String startDate, String endDate,
                                java.util.Set<String> allowedPstFileNames) {
        public static final SearchFilters NONE = new SearchFilters(null, null, null, null, null);

        public static SearchFilters of(String sender, String pstFile, String startDate, String endDate) {
            boolean any = (sender != null && !sender.isBlank())
                    || (pstFile != null && !pstFile.isBlank())
                    || (startDate != null && !startDate.isBlank())
                    || (endDate != null && !endDate.isBlank());
            return any ? new SearchFilters(sender, pstFile, startDate, endDate, null) : NONE;
        }

        public static SearchFilters of(String sender, String pstFile, String startDate, String endDate,
                                       java.util.Set<String> allowedPstFileNames) {
            boolean any = (sender != null && !sender.isBlank())
                    || (pstFile != null && !pstFile.isBlank())
                    || (startDate != null && !startDate.isBlank())
                    || (endDate != null && !endDate.isBlank())
                    || (allowedPstFileNames != null);
            return any ? new SearchFilters(sender, pstFile, startDate, endDate, allowedPstFileNames) : NONE;
        }

        public boolean hasAny() { return this != NONE; }

        public boolean matches(SearchResult r) {
            if (allowedPstFileNames != null && !allowedPstFileNames.contains(r.pstFileName())) {
                return false;
            }
            if (sender != null && !sender.isBlank()) {
                String s = sender.toLowerCase();
                boolean senderMatch = (r.senderName() != null && r.senderName().toLowerCase().contains(s))
                        || (r.senderEmailAddress() != null && r.senderEmailAddress().toLowerCase().contains(s));
                if (!senderMatch) return false;
            }
            if (pstFile != null && !pstFile.isBlank()) {
                if (r.pstFileName() == null || !r.pstFileName().toLowerCase().contains(pstFile.toLowerCase())) {
                    return false;
                }
            }
            return true;
        }
    }
}
