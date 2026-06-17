package hu.fmdev.backend.mcp;

import hu.fmdev.backend.service.EDiscoverySearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@RequiredArgsConstructor
public class EDiscoveryMcpTools {

    private final EDiscoverySearchService searchService;

    @Tool(description = """
            Search the SuliWeb e-Discovery email archive using full-text search with optional filters.
            Supports fuzzy matching, stemming, and accent-insensitive queries (Hungarian and English).
            Returns ranked email hits with subject, sender, date, PST source, and a highlighted snippet.
            Use dateFrom/dateTo in ISO-8601 format (yyyy-MM-dd). Pass null to omit any optional filter.
            topK controls the maximum number of results (default 10, max 50).
            """)
    public List<EDiscoverySearchService.SearchResult> searchEmails(
            String query,
            String sender,
            String pstOwner,
            String pstFileName,
            String dateFrom,
            String dateTo,
            int topK) {
        LocalDate from = dateFrom != null && !dateFrom.isBlank() ? LocalDate.parse(dateFrom) : null;
        LocalDate to   = dateTo   != null && !dateTo.isBlank()   ? LocalDate.parse(dateTo)   : null;
        int limit = topK > 0 ? Math.min(topK, 50) : 10;
        return searchService.search(query, sender, pstOwner, pstFileName, from, to, limit);
    }

    @Bean
    public MethodToolCallbackProvider eDiscoveryToolCallbackProvider() {
        return MethodToolCallbackProvider.builder().toolObjects(this).build();
    }
}
