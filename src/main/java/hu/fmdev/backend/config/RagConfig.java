package hu.fmdev.backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "rag")
public class RagConfig {

    private String ollamaBaseUrl = "http://localhost:11434";
    private String embeddingModel = "bge-m3";
    private int embeddingDimensions = 1024;

    // Smaller chunks = more precise retrieval for email content
    private int chunkSize = 400;
    private int chunkOverlap = 128;

    // Higher topK because hybrid search re-ranks candidates before slicing
    private int searchTopK = 20;
    private double searchMinScore = 0.35;

    // Larger batch is fine: bge-m3 is fast on M4 Max GPU
    private int ingestionBatchSize = 100;
    private int ingestionThreads = 8;
    private int embeddingThreads = 4;
    private int embeddingTimeoutSeconds = 120;

    // Whether to extract and index text from attachments (PDF, DOCX, etc.) during ingestion.
    // Significantly increases indexing time but improves search coverage.
    private boolean includeAttachments = false;

    // Chat (RAG-grounded LLM) settings
    // Recommended models (in order of quality): qwen2.5:14b, llama3.1:8b, gemma2:9b, mistral:7b
    // llama3.2 (3B) is too small for quality Hungarian RAG answers
    private String chatModel = "llama3.1:8b";
    private int chatContextTopK = 8;

    // Maximum number of conversation history turns sent to the LLM (user+assistant pairs)
    private int chatMaxHistoryTurns = 6;

    // HyDE (Hypothetical Document Embedding) – generates a hypothetical answer for better vector search
    private boolean hydeEnabled = true;

    // Qdrant vector database settings
    private boolean qdrantEnabled = false;
    private String qdrantHost = "localhost";
    private int qdrantGrpcPort = 6334;
    private int qdrantRestPort = 6333;
    private String qdrantCollectionName = "document_chunks";


    @Bean
    public WebClient ollamaWebClient() {
        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
