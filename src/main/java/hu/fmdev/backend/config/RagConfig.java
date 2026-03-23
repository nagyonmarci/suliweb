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
    private String embeddingModel = "nomic-embed-text";
    private int embeddingDimensions = 768;

    private int chunkSize = 512;
    private int chunkOverlap = 64;

    private int searchTopK = 10;
    private double searchMinScore = 0.5;

    private int ingestionBatchSize = 50;
    private int ingestionThreads = 4;

    @Bean
    public WebClient ollamaWebClient() {
        return WebClient.builder()
                .baseUrl(ollamaBaseUrl)
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
                .build();
    }
}
