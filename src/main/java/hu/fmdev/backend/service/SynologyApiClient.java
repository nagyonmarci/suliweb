package hu.fmdev.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hu.fmdev.backend.logger.CentralLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class SynologyApiClient {

    private final SynologySettingsService settingsService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private String sid;

    public void login() {
        String url = UriComponentsBuilder
                .fromUriString(settingsService.getEffectiveHost())
                .path("/webapi/auth.cgi")
                .queryParam("api", "SYNO.API.Auth")
                .queryParam("version", "6")
                .queryParam("method", "login")
                .queryParam("account", settingsService.getEffectiveUsername())
                .queryParam("passwd", settingsService.getEffectivePassword())
                .queryParam("session", "FileStation")
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
                .fromUriString(settingsService.getEffectiveHost())
                .path("/webapi/auth.cgi")
                .queryParam("api", "SYNO.API.Auth")
                .queryParam("version", "6")
                .queryParam("method", "logout")
                .queryParam("session", "FileStation")
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
        try {
            String url = UriComponentsBuilder
                    .fromUriString(settingsService.getEffectiveHost())
                    .path("/webapi/entry.cgi")
                    .toUriString();

            org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
            headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

            org.springframework.util.MultiValueMap<String, String> map = new org.springframework.util.LinkedMultiValueMap<>();
            map.add("api", "SYNO.Finder.FileIndexing.Search");
            map.add("version", "1");
            map.add("method", "search");
            map.add("_sid", sid);
            map.add("criteria_list", "[{\"field\":\"SYNOMDExtension\",\"value\":\"" + extension + "\"}]");
            map.add("search_weight_list", "[{\"field\":\"SYNOMDSearchFileName\",\"weight\":8.5,\"trailing_wildcard\":true}]");
            map.add("keyword", "");
            map.add("from", "0");
            map.add("size", "1");

            org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> request = new org.springframework.http.HttpEntity<>(map, headers);
            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode root = objectMapper.readTree(response);

            if (root.path("success").asBoolean()) {
                return root.path("data").path("total").asInt();
            } else {
                CentralLogger.logError("Synology total count sikertelen: " + extension + ", válasz: " + response, null);
                return 0;
            }
        } catch (Exception e) {
            CentralLogger.logError("Synology total count hiba: " + extension, e);
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
        int batchSize = settingsService.getEffectiveBatchSize();

        while (offset < total) {
            try {
                String url = UriComponentsBuilder
                        .fromUriString(settingsService.getEffectiveHost())
                        .path("/webapi/entry.cgi")
                        .toUriString();

                org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
                headers.setContentType(org.springframework.http.MediaType.APPLICATION_FORM_URLENCODED);

                org.springframework.util.MultiValueMap<String, String> map = new org.springframework.util.LinkedMultiValueMap<>();
                map.add("api", "SYNO.Finder.FileIndexing.Search");
                map.add("version", "1");
                map.add("method", "search");
                map.add("_sid", sid);
                map.add("criteria_list", "[{\"field\":\"SYNOMDExtension\",\"value\":\"" + extension + "\"}]");
                map.add("fields", "[\"SYNOMDFSName\",\"SYNOMDFSSize\",\"SYNOMDPath\",\"SYNOMDSharePath\",\"SYNOMDExtension\",\"SYNOMDIsDir\",\"SYNOMDOwnerUserName\",\"SYNOMDContentCreationDate\",\"SYNOMDContentModificationDate\"]");
                map.add("search_weight_list", "[{\"field\":\"SYNOMDSearchFileName\",\"weight\":8.5,\"trailing_wildcard\":true}]");
                map.add("keyword", "");
                map.add("from", String.valueOf(offset));
                map.add("size", String.valueOf(batchSize));

                org.springframework.http.HttpEntity<org.springframework.util.MultiValueMap<String, String>> request = new org.springframework.http.HttpEntity<>(map, headers);
                String response = restTemplate.postForObject(url, request, String.class);
                JsonNode root = objectMapper.readTree(response);

                if (root.path("success").asBoolean()) {
                    JsonNode hits = root.path("data").path("hits");
                    if (hits.isArray()) {
                        for (JsonNode hit : hits) {
                            allHits.add(hit);
                        }
                    }
                } else {
                    CentralLogger.logError("Synology keresés sikertelen, offset: " + offset + ", válasz: " + response, null);
                }
            } catch (Exception e) {
                CentralLogger.logError("Synology keresés hiba, offset: " + offset, e);
            }

            offset += batchSize;
        }

        return allHits;
    }

    @PreDestroy
    public void cleanup() {
        logout();
    }
}
