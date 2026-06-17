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

    // Chat (GraphRAG-grounded LLM) settings
    // Recommended models (in order of quality): qwen2.5:14b, llama3.1:8b, gemma2:9b, mistral:7b
    // llama3.2 (3B) is too small for quality Hungarian RAG answers
    private String chatModel = "llama3.1:8b";

    // Number of context emails retrieved from the Knowledge Graph per chat turn
    private int chatContextTopK = 8;

    // Maximum number of conversation history turns sent to the LLM (user+assistant pairs)
    private int chatMaxHistoryTurns = 6;

    @Bean
    public WebClient ollamaWebClient() {
        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
