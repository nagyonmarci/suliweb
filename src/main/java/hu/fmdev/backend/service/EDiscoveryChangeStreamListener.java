package hu.fmdev.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.mongodb.client.MongoClient;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import hu.fmdev.backend.logger.CentralLogger;
import org.bson.BsonDocument;
import org.bson.BsonObjectId;
import org.bson.BsonValue;
import org.bson.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

@Component
public class EDiscoveryChangeStreamListener implements ApplicationRunner {

    private final MongoClient mongoClient;
    private final EDiscoveryIngestionService ingestionService;
    private final ElasticsearchClient esClient;

    public EDiscoveryChangeStreamListener(MongoClient mongoClient,
                                          EDiscoveryIngestionService ingestionService,
                                          ElasticsearchClient esClient) {
        this.mongoClient   = mongoClient;
        this.ingestionService = ingestionService;
        this.esClient      = esClient;
    }

    @Override
    public void run(ApplicationArguments args) {
        Thread.ofVirtual().name("ediscovery-change-stream").start(this::watch);
    }

    private void watch() {
        var pipeline = List.of(
            Aggregates.match(Filters.in("operationType",
                List.of("insert", "update", "replace", "delete")))
        );
        BsonDocument resumeToken = null;

        while (!Thread.currentThread().isInterrupted()) {
            try {
                var stream = mongoClient.getDatabase("emails")
                        .getCollection("emails")
                        .watch(pipeline);
                if (resumeToken != null) stream = stream.resumeAfter(resumeToken);

                for (ChangeStreamDocument<Document> event : stream) {
                    resumeToken = event.getResumeToken();
                    String mongoId = extractId(event.getDocumentKey());
                    switch (event.getOperationType().getValue()) {
                        case "insert", "update", "replace" -> {
                            if (!ingestionService.isRunning()) ingestionService.reIngest(mongoId);
                        }
                        case "delete" -> deleteFromEs(mongoId);
                    }
                }
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted()) break;
                CentralLogger.logWarn("e-Discovery change stream hiba, újracsatlakozás 5s múlva: " + e.getMessage());
                try { Thread.sleep(5_000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private String extractId(BsonDocument key) {
        BsonValue v = key.get("_id");
        return v instanceof BsonObjectId oid ? oid.getValue().toHexString() : v.asString().getValue();
    }

    private void deleteFromEs(String mongoId) {
        try {
            esClient.deleteByQuery(d -> d
                    .index("email_archive")
                    .query(q -> q.term(t -> t.field("mongoEmailId").value(mongoId))));
        } catch (IOException e) {
            CentralLogger.logWarn("e-Discovery: ES törlés sikertelen mongoId=" + mongoId + ": " + e.getMessage());
        }
    }
}
