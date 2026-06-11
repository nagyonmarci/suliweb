package hu.fmdev.backend.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.*;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import hu.fmdev.backend.logger.CentralLogger;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Configuration
public class ElasticsearchConfig {

    @Value("${ediscovery.es.url:http://localhost:9200}")
    private String esUrl;

    @Value("${ediscovery.python.url:http://localhost:8001}")
    private String pythonUrl;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        RestClient restClient = RestClient.builder(HttpHost.create(esUrl)).build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    @Bean("pythonProcessorClient")
    public WebClient pythonProcessorClient() {
        return WebClient.builder()
                .baseUrl(pythonUrl)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void ensureEmailArchiveIndex() {
        try {
            ElasticsearchClient client = elasticsearchClient();
            boolean exists = client.indices().exists(e -> e.index("email_archive")).value();
            if (exists) return;

            client.indices().create(CreateIndexRequest.of(ci -> ci
                    .index("email_archive")
                    .settings(s -> s
                            .numberOfShards("1")
                            .numberOfReplicas("0")
                            .analysis(a -> a
                                    .filter("hungarian_stop", f -> f
                                            .definition(fd -> fd.stop(st -> st.stopwords("_hungarian_"))))
                                    // Stemmelt analyzer ékezetes szavakra (asciifolding UTÁN stemmer — normalizált input)
                                    .analyzer("hungarian_stemmed", an -> an
                                            .custom(cu -> cu
                                                    .tokenizer("standard")
                                                    .filter("lowercase", "hungarian_stop", "hungarian")))
                                    // Asciifolded analyzer ékezet nélküli kereséshez (stemmelés nélkül)
                                    .analyzer("hungarian_ascii", an -> an
                                            .custom(cu -> cu
                                                    .tokenizer("standard")
                                                    .filter("lowercase", "asciifolding", "hungarian_stop")))))
                    .mappings(m -> m
                            .properties(buildMappings()))));

            CentralLogger.logInfo("Elasticsearch 'email_archive' index létrehozva");
        } catch (Exception e) {
            CentralLogger.logError("Elasticsearch index létrehozása sikertelen", e);
        }
    }

    private Map<String, Property> buildMappings() {
        return Map.ofEntries(
                Map.entry("messageId",    Property.of(p -> p.keyword(k -> k))),
                Map.entry("mongoEmailId", Property.of(p -> p.keyword(k -> k))),
                Map.entry("subject",      hungarianMultiField()),
                Map.entry("bodyDelta",    hungarianMultiField()),
                Map.entry("sender",       Property.of(p -> p.keyword(k -> k))),
                Map.entry("senderName",   hungarianMultiField()),
                Map.entry("recipients",   Property.of(p -> p.keyword(k -> k))),
                Map.entry("date",         Property.of(p -> p.date(d -> d))),
                Map.entry("pstFileName",  Property.of(p -> p.keyword(k -> k))),
                Map.entry("pstOwner",     Property.of(p -> p.keyword(k -> k))),
                Map.entry("threadId",     Property.of(p -> p.keyword(k -> k))),
                Map.entry("attachments",  Property.of(p -> p.nested(n -> n
                        .properties(Map.of(
                                "filename",        Property.of(fp -> fp.keyword(k -> k)),
                                "sha256",          Property.of(fp -> fp.keyword(k -> k)),
                                "markdownContent", hungarianMultiField())))))
        );
    }

    // Stemmelt fő mező + .ascii alfeld ékezet nélküli kereséshez
    private static Property hungarianMultiField() {
        return Property.of(p -> p.text(t -> t
                .analyzer("hungarian_stemmed")
                .fields("ascii", f -> f.text(tf -> tf.analyzer("hungarian_ascii")))));
    }
}
