package hu.fmdev.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "kg.ingestion")
public class KgIngestionProperties {

    private int batchSize = 100;
    private int maxConcurrentWrites = 4;

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public int getMaxConcurrentWrites() { return maxConcurrentWrites; }
    public void setMaxConcurrentWrites(int maxConcurrentWrites) { this.maxConcurrentWrites = maxConcurrentWrites; }
}
