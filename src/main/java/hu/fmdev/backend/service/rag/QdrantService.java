package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import hu.fmdev.backend.logger.CentralLogger;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Points.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.ValueFactory.value;
import static io.qdrant.client.VectorsFactory.vectors;

/**
 * Manages a Qdrant vector database collection for fast ANN (Approximate Nearest Neighbor) search.
 * Replaces the brute-force in-memory cosine similarity approach with HNSW-indexed vector search.
 *
 * Only active when rag.qdrantEnabled=true in configuration.
 */
@Service
public class QdrantService {

    private final RagConfig ragConfig;
    private QdrantClient client;
    private boolean available = false;

    public QdrantService(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    @PostConstruct
    public void init() {
        if (!ragConfig.isQdrantEnabled()) {
            CentralLogger.logInfo("Qdrant integration disabled (rag.qdrantEnabled=false)");
            return;
        }

        try {
            client = new QdrantClient(
                    QdrantGrpcClient.newBuilder(ragConfig.getQdrantHost(), ragConfig.getQdrantGrpcPort(), false)
                            .build());
            ensureCollection();
            available = true;
            CentralLogger.logInfo("Qdrant connected at " + ragConfig.getQdrantHost() + ":" + ragConfig.getQdrantGrpcPort());
        } catch (Exception e) {
            CentralLogger.logError("Failed to connect to Qdrant", e);
            available = false;
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                CentralLogger.logWarn("Error closing Qdrant client: " + e.getMessage());
            }
        }
    }

    public boolean isAvailable() {
        return available && ragConfig.isQdrantEnabled();
    }

    /**
     * Creates the collection if it doesn't exist, with HNSW indexing and cosine distance.
     */
    private void ensureCollection() throws InterruptedException, ExecutionException, TimeoutException {
        String collectionName = ragConfig.getQdrantCollectionName();
        boolean exists = client.collectionExistsAsync(collectionName).get(10, TimeUnit.SECONDS);

        if (!exists) {
            client.createCollectionAsync(collectionName,
                    VectorParams.newBuilder()
                            .setDistance(Distance.Cosine)
                            .setSize(ragConfig.getEmbeddingDimensions())
                            .build()
            ).get(10, TimeUnit.SECONDS);
            CentralLogger.logInfo("Created Qdrant collection: " + collectionName
                    + " (dims=" + ragConfig.getEmbeddingDimensions() + ", distance=Cosine)");
        }
    }

    /**
     * Upserts a single point (chunk) into Qdrant.
     *
     * @param chunkId    MongoDB document chunk _id (used as UUID seed)
     * @param embedding  the embedding vector
     * @param payload    metadata fields (emailId, content, sourceType, etc.)
     */
    public void upsert(String chunkId, List<Double> embedding, Map<String, String> payload) {
        if (!isAvailable()) return;

        try {
            float[] floatVec = toFloatArray(embedding);
            Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap = new HashMap<>();
            for (var entry : payload.entrySet()) {
                if (entry.getValue() != null) {
                    payloadMap.put(entry.getKey(), value(entry.getValue()));
                }
            }

            PointStruct point = PointStruct.newBuilder()
                    .setId(id(UUID.fromString(toUuid(chunkId))))
                    .setVectors(vectors(floatVec))
                    .putAllPayload(payloadMap)
                    .build();

            client.upsertAsync(ragConfig.getQdrantCollectionName(), List.of(point))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            CentralLogger.logWarn("Qdrant upsert failed for chunk " + chunkId + ": " + e.getMessage());
        }
    }

    /**
     * Batch upsert of multiple points.
     */
    public void upsertBatch(List<ChunkPoint> points) {
        if (!isAvailable() || points.isEmpty()) return;

        try {
            List<PointStruct> qdrantPoints = new ArrayList<>(points.size());
            for (ChunkPoint cp : points) {
                float[] floatVec = toFloatArray(cp.embedding());
                Map<String, io.qdrant.client.grpc.JsonWithInt.Value> payloadMap = new HashMap<>();
                for (var entry : cp.payload().entrySet()) {
                    if (entry.getValue() != null) {
                        payloadMap.put(entry.getKey(), value(entry.getValue()));
                    }
                }
                qdrantPoints.add(PointStruct.newBuilder()
                        .setId(id(UUID.fromString(toUuid(cp.chunkId()))))
                        .setVectors(vectors(floatVec))
                        .putAllPayload(payloadMap)
                        .build());
            }

            client.upsertAsync(ragConfig.getQdrantCollectionName(), qdrantPoints)
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            CentralLogger.logError("Qdrant batch upsert failed for " + points.size() + " points", e);
        }
    }

    /**
     * Searches Qdrant for the nearest vectors to the given query embedding.
     *
     * @param queryEmbedding the query vector
     * @param topK           max results to return
     * @return list of search hits with payload and score
     */
    public List<QdrantHit> search(List<Double> queryEmbedding, int topK) {
        if (!isAvailable()) return List.of();

        try {
            float[] floatVec = toFloatArray(queryEmbedding);

            List<ScoredPoint> results = client.searchAsync(
                    SearchPoints.newBuilder()
                            .setCollectionName(ragConfig.getQdrantCollectionName())
                            .addAllVector(toFloatList(floatVec))
                            .setLimit(topK)
                            .setWithPayload(WithPayloadSelector.newBuilder().setEnable(true).build())
                            .build()
            ).get(10, TimeUnit.SECONDS);

            List<QdrantHit> hits = new ArrayList<>(results.size());
            for (ScoredPoint sp : results) {
                Map<String, String> payload = new HashMap<>();
                sp.getPayloadMap().forEach((k, v) -> payload.put(k, v.getStringValue()));
                String pointId = sp.getId().getUuid();
                hits.add(new QdrantHit(pointId, payload, sp.getScore()));
            }
            return hits;
        } catch (Exception e) {
            CentralLogger.logError("Qdrant search failed", e);
            return List.of();
        }
    }

    /**
     * Deletes points by their chunk IDs.
     */
    public void deleteByChunkIds(List<String> chunkIds) {
        if (!isAvailable() || chunkIds.isEmpty()) return;

        try {
            List<PointId> pointIds = chunkIds.stream()
                    .map(cid -> id(UUID.fromString(toUuid(cid))))
                    .toList();
            client.deleteAsync(ragConfig.getQdrantCollectionName(), pointIds)
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            CentralLogger.logWarn("Qdrant delete failed: " + e.getMessage());
        }
    }

    /**
     * Deletes the entire collection and recreates it (for reset-all).
     */
    public void resetCollection() {
        if (!isAvailable()) return;

        try {
            String collectionName = ragConfig.getQdrantCollectionName();
            client.deleteCollectionAsync(collectionName).get(10, TimeUnit.SECONDS);
            ensureCollection();
            CentralLogger.logInfo("Qdrant collection reset: " + collectionName);
        } catch (Exception e) {
            CentralLogger.logError("Qdrant collection reset failed", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DTOs
    // ─────────────────────────────────────────────────────────────────────────

    public record QdrantHit(String pointId, Map<String, String> payload, float score) {}

    public record ChunkPoint(String chunkId, List<Double> embedding, Map<String, String> payload) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private float[] toFloatArray(List<Double> doubles) {
        float[] arr = new float[doubles.size()];
        for (int i = 0; i < doubles.size(); i++) {
            arr[i] = doubles.get(i).floatValue();
        }
        return arr;
    }

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float f : arr) list.add(f);
        return list;
    }

    /**
     * Converts a MongoDB ObjectId hex string to a deterministic UUID.
     * Pads or truncates to 32 hex chars (128 bits) for UUID format.
     */
    private String toUuid(String mongoId) {
        // Pad the 24-char ObjectId to 32 chars with leading zeros
        String hex = mongoId.replaceAll("[^a-fA-F0-9]", "");
        if (hex.length() < 32) hex = "0".repeat(32 - hex.length()) + hex;
        if (hex.length() > 32) hex = hex.substring(0, 32);
        return hex.substring(0, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 16) + "-" + hex.substring(16, 20) + "-"
                + hex.substring(20, 32);
    }
}
