package hu.fmdev.backend.config;

import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.TextIndexDefinition;
import org.springframework.stereotype.Configuration;

/**
 * Ensures required MongoDB indexes exist at application startup.
 * The text index on document_chunks is critical for the keyword search lane in hybrid RAG search.
 */
@Configuration
public class MongoIndexConfig {

    private final MongoTemplate mongoTemplate;

    public MongoIndexConfig(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureIndexes() {
        try {
            var indexOps = mongoTemplate.indexOps("document_chunks");
            indexOps.ensureIndex(new TextIndexDefinition.TextIndexDefinitionBuilder()
                    .onField("content", 3F)
                    .onField("emailSubject", 2F)
                    .onField("senderName", 1F)
                    .onField("senderEmailAddress", 1F)
                    .onField("pstFileName", 1F)
                    .build());
            CentralLogger.logInfo("MongoDB text index ensured on document_chunks collection");
        } catch (Exception e) {
            CentralLogger.logError("Failed to create text index on document_chunks", e);
        }
    }
}
