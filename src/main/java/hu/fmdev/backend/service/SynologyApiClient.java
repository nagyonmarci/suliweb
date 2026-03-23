package hu.fmdev.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.config.SynologyConfig;
import hu.fmdev.backend.logger.CentralLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SynologyApiClient {

    private final SynologyConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private String sid;

    public void login() {
        String url = UriComponentsBuilder
                .fromHttpUrl(config.getHost())
                .path("/webapi/auth.cgi")
                .queryParam("api", "SYNO.API.Auth")
                .queryParam("version", "3")
                .queryParam("method", "login")
                .queryParam("account", config.getUsername())
                .queryParam("passwd", config.getPassword())
                .queryParam("session", "FileIndexing")
                .queryParam("format", "cookie")
                .toUriString();

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("success").asBoolean()) {
                this.sid = root.path("data").path("sid").asText();
                CentralLogger.logInfo("Synology login sikeres.");
            } else {
                int errorCode = root.path("error").path("code").asInt();
                throw new RuntimeException("Synology login sikertelen, hibakód: " + errorCode);
            }
        } catch (Exception e) {
            CentralLogger.logError("Synology login hiba", e);
            throw new RuntimeException("Synology login sikertelen: " + e.getMessage(), e);
        }
    }

    public void logout() {
        if (sid == null) return;

        String url = UriComponentsBuilder
                .fromHttpUrl(config.getHost())
                .path("/webapi/auth.cgi")
                .queryParam("api", "SYNO.API.Auth")
                .queryParam("version", "3")
                .queryParam("method", "logout")
                .queryParam("session", "FileIndexing")
                .queryParam("_sid", sid)
                .toUriString();

        try {
            restTemplate.getForObject(url, String.class);
            CentralLogger.logInfo("Synology logout sikeres.");
        } catch (Exception e) {
            CentralLogger.logError("Synology logout hiba", e);
        } finally {
            this.sid = null;
        }
    }

    public int getTotalCount(String extension) {
        String url = buildSearchUrl(extension, 0, 0);

        try {
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("success").asBoolean()) {
                return root.path("data").path("total").asInt();
            } else {
                CentralLogger.logError("Synology keresés sikertelen: " + extension, null);
                return 0;
            }
        } catch (Exception e) {
            CentralLogger.logError("Synology keresés hiba: " + extension, e);
            return 0;
        }
    }

    public List<JsonNode> searchFiles(String extension) {
        List<JsonNode> allHits = new ArrayList<>();
        int total = getTotalCount(extension);

        if (total == 0) {
            CentralLogger.logInfo("Nem található " + extension + " fájl a NAS-on.");
            return allHits;
        }

        CentralLogger.logInfo("Összesen " + total + " db " + extension + " fájl található.");

        int offset = 0;
        int batchSize = config.getBatchSize();

        while (offset < total) {
            String url = buildSearchUrl(extension, offset, batchSize);

            try {
                String response = restTemplate.getForObject(url, String.class);
                JsonNode root = objectMapper.readTree(response);

                if (root.path("success").asBoolean()) {
                    JsonNode hits = root.path("data").path("hits");
                    if (hits.isArray()) {
                        for (JsonNode hit : hits) {
                            allHits.add(hit);
                        }
                    }
                } else {
                    CentralLogger.logError("Synology keresés sikertelen, offset: " + offset, null);
                }
            } catch (Exception e) {
                CentralLogger.logError("Synology keresés hiba, offset: " + offset, e);
            }

            offset += batchSize;
        }

        return allHits;
    }

    private String buildSearchUrl(String extension, int offset, int limit) {
        String criteriaValue = "[\"SYNOMDExtension:=" + extension + "\"]";

        return UriComponentsBuilder
                .fromHttpUrl(config.getHost())
                .path("/webapi/entry.cgi")
                .queryParam("api", "SYNO.Finder.FileIndexing.Search")
                .queryParam("version", "1")
                .queryParam("method", "search")
                .queryParam("criteria_logic", "or")
                .queryParam("criteria", criteriaValue)
                .queryParam("offset", offset)
                .queryParam("limit", limit)
                .queryParam("additional", "[\"SYNOMDExtension\",\"SYNOMDFSName\",\"SYNOMDFSSize\",\"SYNOMDPath\",\"SYNOMDSharePath\",\"SYNOMDOwnerUserName\",\"SYNOMDLastModifiedDate\"]")
                .queryParam("_sid", sid)
                .toUriString();
    }

    @PreDestroy
    public void cleanup() {
        logout();
    }
}
