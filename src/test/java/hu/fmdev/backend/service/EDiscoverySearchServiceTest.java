package hu.fmdev.backend.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.JsonpUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EDiscoverySearchServiceTest {

    @Mock private ElasticsearchClient esClient;
    @SuppressWarnings("unchecked")
    private final SearchResponse<Map<String, Object>> response = mock(SearchResponse.class);
    @SuppressWarnings("unchecked")
    private final Hit<Map<String, Object>> hit = mock(Hit.class);

    private EDiscoverySearchService service;

    @BeforeEach
    void setUp() {
        service = new EDiscoverySearchService(esClient);
    }

    @SuppressWarnings("unchecked")
    private SearchRequest captureRequest() throws IOException {
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);
        when(response.hits()).thenReturn(mock(co.elastic.clients.elasticsearch.core.search.HitsMetadata.class));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);
        service.search("kontrakt", null, null, null, null, null, 10);
        verify(esClient).search(captor.capture(), any(Class.class));
        return captor.getValue();
    }

    @Test
    void search_appliesFuzzinessForTypoTolerance() throws IOException {
        SearchRequest req = captureRequest();
        String json = JsonpUtils.toString(req);

        assertTrue(json.contains("\"fuzziness\":\"AUTO\""), "expected fuzziness AUTO in: " + json);
        assertTrue(json.contains("\"prefix_length\":2"), "expected prefix_length 2 in: " + json);
    }

    @Test
    void search_noFilters_sendsPlainMultiMatchQuery() throws IOException {
        SearchRequest req = captureRequest();
        String json = JsonpUtils.toString(req);

        assertTrue(json.contains("multi_match"));
        assertFalse(json.contains("\"bool\""), "no filters were given, so no bool/filter wrapper expected");
    }

    @Test
    void search_withSenderFilter_wrapsQueryInBoolFilter() throws IOException {
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);
        when(response.hits()).thenReturn(mock(co.elastic.clients.elasticsearch.core.search.HitsMetadata.class));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

        service.search("kontrakt", "sender@example.com", null, null, null, null, 10);

        verify(esClient).search(captor.capture(), any(Class.class));
        String json = JsonpUtils.toString(captor.getValue());
        assertTrue(json.contains("\"bool\""));
        assertTrue(json.contains("sender@example.com"));
    }

    @Test
    void search_withDateRange_includesRangeFilter() throws IOException {
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);
        when(response.hits()).thenReturn(mock(co.elastic.clients.elasticsearch.core.search.HitsMetadata.class));
        ArgumentCaptor<SearchRequest> captor = ArgumentCaptor.forClass(SearchRequest.class);

        service.search("kontrakt", null, null, null,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), 10);

        verify(esClient).search(captor.capture(), any(Class.class));
        String json = JsonpUtils.toString(captor.getValue());
        assertTrue(json.contains("\"range\""));
        assertTrue(json.contains("2026-01-01"));
        assertTrue(json.contains("2026-01-31"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void search_mapsHitsWithHighlightToSearchResults() throws IOException {
        Map<String, Object> source = Map.of(
                "mongoEmailId", "m1",
                "subject", "Szerződés",
                "senderName", "Kovács Péter",
                "sender", "kovacs@example.com",
                "date", "2026-01-01",
                "pstFileName", "archive.pst",
                "pstOwner", "archive");
        when(hit.source()).thenReturn(source);
        when(hit.id()).thenReturn("es-id-1");
        when(hit.score()).thenReturn(7.5);
        when(hit.highlight()).thenReturn(Map.of("bodyDelta", List.of("...highlighted <em>snippet</em>...")));

        co.elastic.clients.elasticsearch.core.search.HitsMetadata<Map<String, Object>> hitsMeta = mock(co.elastic.clients.elasticsearch.core.search.HitsMetadata.class);
        when(hitsMeta.hits()).thenReturn(List.of(hit));
        when(response.hits()).thenReturn(hitsMeta);
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);

        List<EDiscoverySearchService.SearchResult> results = service.search("szerződés", null, null, null, null, null, 10);

        assertEquals(1, results.size());
        EDiscoverySearchService.SearchResult r = results.get(0);
        assertEquals("es-id-1", r.esId());
        assertEquals("m1", r.mongoEmailId());
        assertEquals("Szerződés", r.subject());
        assertEquals(7.5f, r.score());
        assertTrue(r.snippet().contains("snippet"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void search_hitWithoutSource_isSkipped() throws IOException {
        when(hit.source()).thenReturn(null);
        co.elastic.clients.elasticsearch.core.search.HitsMetadata<Map<String, Object>> hitsMeta = mock(co.elastic.clients.elasticsearch.core.search.HitsMetadata.class);
        when(hitsMeta.hits()).thenReturn(List.of(hit));
        when(response.hits()).thenReturn(hitsMeta);
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);

        List<EDiscoverySearchService.SearchResult> results = service.search("q", null, null, null, null, null, 10);

        assertTrue(results.isEmpty());
    }

    @SuppressWarnings("unchecked")
    @Test
    void search_hitWithoutHighlight_emptySnippet() throws IOException {
        when(hit.source()).thenReturn(Map.of("subject", "No highlight here"));
        when(hit.id()).thenReturn("id1");
        when(hit.score()).thenReturn(1.0);
        when(hit.highlight()).thenReturn(Map.of());
        co.elastic.clients.elasticsearch.core.search.HitsMetadata<Map<String, Object>> hitsMeta = mock(co.elastic.clients.elasticsearch.core.search.HitsMetadata.class);
        when(hitsMeta.hits()).thenReturn(List.of(hit));
        when(response.hits()).thenReturn(hitsMeta);
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenReturn(response);

        List<EDiscoverySearchService.SearchResult> results = service.search("q", null, null, null, null, null, 10);

        assertEquals(1, results.size());
        assertEquals("", results.get(0).snippet());
    }

    @Test
    void search_ioExceptionFromEsClient_returnsEmptyList() throws IOException {
        when(esClient.search(any(SearchRequest.class), any(Class.class))).thenThrow(new IOException("ES unreachable"));

        List<EDiscoverySearchService.SearchResult> results = service.search("q", null, null, null, null, null, 10);

        assertTrue(results.isEmpty());
    }
}
