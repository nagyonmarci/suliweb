package hu.fmdev.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.*;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HighlightField;
import co.elastic.clients.util.NamedValue;
import hu.fmdev.backend.logger.CentralLogger;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.LocalDate;
import java.util.*;

@Service
public class EDiscoverySearchService {

    private final ElasticsearchClient esClient;

    public EDiscoverySearchService(ElasticsearchClient esClient) {
        this.esClient = esClient;
    }

    public List<SearchResult> search(String query, String sender, String pstOwner,
                                     String pstFileName, LocalDate dateFrom, LocalDate dateTo,
                                     int topK) {
        try {
            List<Query> filters = new ArrayList<>();
            if (sender != null && !sender.isBlank()) {
                filters.add(Query.of(q -> q.term(t -> t.field("sender").value(sender))));
            }
            if (pstOwner != null && !pstOwner.isBlank()) {
                filters.add(Query.of(q -> q.term(t -> t.field("pstOwner").value(pstOwner))));
            }
            if (pstFileName != null && !pstFileName.isBlank()) {
                filters.add(Query.of(q -> q.term(t -> t.field("pstFileName").value(pstFileName))));
            }
            if (dateFrom != null || dateTo != null) {
                filters.add(Query.of(q -> q.range(r -> {
                    var rb = r.date(d -> {
                        var b = d.field("date");
                        if (dateFrom != null) b = b.gte(dateFrom.toString());
                        if (dateTo != null)   b = b.lte(dateTo.toString());
                        return b;
                    });
                    return rb;
                })));
            }

            // Stemmelt + ascii alfeld egyszerre: ragozott és ékezet nélküli keresés is működik
            Query multiMatch = Query.of(q -> q.multiMatch(mm -> mm
                    .query(query)
                    .fields("subject^3", "subject.ascii^2",
                            "bodyDelta^2", "bodyDelta.ascii",
                            "senderName", "senderName.ascii")
                    .type(TextQueryType.BestFields)));

            Query finalQuery = filters.isEmpty()
                    ? multiMatch
                    : Query.of(q -> q.bool(b -> b.must(multiMatch).filter(filters)));

            @SuppressWarnings("unchecked")
            Class<Map<String, Object>> mapClass = (Class<Map<String, Object>>) (Class<?>) Map.class;
            SearchResponse<Map<String, Object>> resp = esClient.search(SearchRequest.of(s -> s
                    .index("email_archive")
                    .query(finalQuery)
                    .size(topK)
                    .highlight(h -> h
                            .fields(NamedValue.of("bodyDelta", HighlightField.of(f -> f
                                    .numberOfFragments(1)
                                    .fragmentSize(200)))))),
                    mapClass);

            List<SearchResult> results = new ArrayList<>();
            for (Hit<Map<String, Object>> hit : resp.hits().hits()) {
                Map<String, Object> src = hit.source();
                if (src == null) continue;
                String snippet = "";
                Map<String, List<String>> hl = hit.highlight();
                if (hl != null && hl.containsKey("bodyDelta")) {
                    List<String> frags = hl.get("bodyDelta");
                    if (frags != null && !frags.isEmpty()) snippet = frags.get(0);
                }
                Double rawScore = hit.score();
                results.add(new SearchResult(
                        hit.id(),
                        str(src, "mongoEmailId"),
                        str(src, "subject"),
                        str(src, "senderName"),
                        str(src, "sender"),
                        str(src, "date"),
                        str(src, "pstFileName"),
                        str(src, "pstOwner"),
                        snippet,
                        rawScore != null ? rawScore.floatValue() : 0f));
            }
            return results;
        } catch (IOException e) {
            CentralLogger.logError("e-Discovery keresés hiba", e);
            return List.of();
        }
    }

    private String str(Map<?, ?> src, String key) {
        Object v = src.get(key);
        return v != null ? v.toString() : "";
    }

    public record SearchResult(String esId, String mongoEmailId, String subject,
                               String senderName, String sender, String date,
                               String pstFileName, String pstOwner,
                               String snippet, float score) {}
}
