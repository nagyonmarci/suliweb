package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.domain.DocumentChunk;
import hu.fmdev.backend.domain.Email;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.EmailRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagSearchService {

    private final EmbeddingService embeddingService;
    private final MongoTemplate mongoTemplate;
    private final EmailRepository emailRepository;
    private final RagConfig ragConfig;

    public RagSearchService(EmbeddingService embeddingService,
                            MongoTemplate mongoTemplate,
                            EmailRepository emailRepository,
                            RagConfig ragConfig) {
        this.embeddingService = embeddingService;
        this.mongoTemplate = mongoTemplate;
        this.emailRepository = emailRepository;
        this.ragConfig = ragConfig;
    }

    /**
     * Performs semantic search using MongoDB Atlas Vector Search.
     * Embeds the query, then uses $vectorSearch aggregation to find similar chunks.
     */
    public List<SearchResult> search(String query, int topK) {
        List<Double> queryEmbedding = embeddingService.embed(query);
        if (queryEmbedding.isEmpty()) {
            CentralLogger.logWarn("Failed to embed search query");
            return List.of();
        }

        int k = topK > 0 ? topK : ragConfig.getSearchTopK();

        try {
            // MongoDB Atlas Vector Search aggregation pipeline
            Document vectorSearch = new Document("$vectorSearch", new Document()
                    .append("index", "chunk_vector_index")
                    .append("path", "embedding")
                    .append("queryVector", queryEmbedding)
                    .append("numCandidates", k * 10)
                    .append("limit", k));

            Document addScore = new Document("$addFields",
                    new Document("score", new Document("$meta", "vectorSearchScore")));

            List<Document> pipeline = List.of(vectorSearch, addScore);

            List<Document> results = mongoTemplate.getDb()
                    .getCollection("document_chunks")
                    .aggregate(pipeline)
                    .into(new ArrayList<>());

            return results.stream()
                    .map(this::toSearchResult)
                    .filter(r -> r.score() >= ragConfig.getSearchMinScore())
                    .toList();
        } catch (Exception e) {
            CentralLogger.logError("Vector search failed", e);
            return List.of();
        }
    }

    /**
     * Performs semantic search and groups results by email, returning enriched email context.
     */
    public List<EmailSearchResult> searchEmails(String query, int topK) {
        List<SearchResult> chunkResults = search(query, topK);

        // Group by emailId and pick the best score per email
        Map<String, List<SearchResult>> grouped = chunkResults.stream()
                .collect(Collectors.groupingBy(SearchResult::emailId));

        List<EmailSearchResult> emailResults = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String emailId = entry.getKey();
            List<SearchResult> chunks = entry.getValue();
            double bestScore = chunks.stream().mapToDouble(SearchResult::score).max().orElse(0);

            Email email = emailRepository.findById(emailId).orElse(null);
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

    /**
     * Builds a context string for LLM consumption from search results.
     */
    public String buildContext(String query, int topK) {
        List<SearchResult> results = search(query, topK);
        if (results.isEmpty()) {
            return "Nem található releváns tartalom.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("A keresés alapján talált releváns részletek:\n\n");

        for (int i = 0; i < results.size(); i++) {
            SearchResult r = results.get(i);
            sb.append("--- Találat ").append(i + 1).append(" (relevancia: ")
              .append(String.format("%.2f", r.score())).append(") ---\n");
            sb.append("Email tárgy: ").append(r.emailSubject()).append("\n");
            sb.append("Feladó: ").append(r.senderName()).append("\n");
            sb.append("Forrás: ").append(r.sourceType());
            if (r.attachmentFileName() != null) {
                sb.append(" (").append(r.attachmentFileName()).append(")");
            }
            sb.append("\n");
            sb.append("Tartalom: ").append(r.content()).append("\n\n");
        }

        return sb.toString();
    }

    private SearchResult toSearchResult(Document doc) {
        return new SearchResult(
                doc.getString("_id"),
                doc.getString("emailId"),
                doc.getString("sourceType"),
                doc.getString("attachmentFileName"),
                doc.getString("content"),
                doc.getString("emailSubject"),
                doc.getString("senderName"),
                doc.getString("senderEmailAddress"),
                doc.getString("pstFileName"),
                doc.getDouble("score") != null ? doc.getDouble("score") : 0.0
        );
    }

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
