package hu.fmdev.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import hu.fmdev.backend.config.SynologyConfig;
import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SynologyPstFinderService {

    private final SynologyApiClient synologyApiClient;
    private final SynologyConfig synologyConfig;
    private final FileInfoRepository fileInfoRepository;
    private final PstFinderService pstFinderService;

    public List<FileInfo> findPstFilesOnNas() {
        List<FileInfo> allFiles = new ArrayList<>();
        String[] extensions = synologyConfig.getSearchExtensions().split(",");

        synologyApiClient.login();
        try {
            for (String extension : extensions) {
                String ext = extension.trim();
                CentralLogger.logInfo("Synology keresés indítása: " + ext);

                List<JsonNode> hits = synologyApiClient.searchFiles(ext);

                for (JsonNode hit : hits) {
                    FileInfo fileInfo = mapHitToFileInfo(hit);
                    if (fileInfo != null) {
                        allFiles.add(fileInfo);
                    }
                }

                CentralLogger.logInfo(ext + " keresés kész, találatok: " + hits.size());
            }
        } finally {
            synologyApiClient.logout();
        }

        CentralLogger.logInfo("Synology keresés összesen: " + allFiles.size() + " fájl.");
        return allFiles;
    }

    public void findAndSaveFiles() {
        List<FileInfo> files = findPstFilesOnNas();

        for (FileInfo fileInfo : files) {
            pstFinderService.saveOrUpdateFileInfos(List.of(fileInfo), List.of());
        }

        CentralLogger.logInfo("Synology fájlok mentve az adatbázisba: " + files.size() + " db.");
    }

    private FileInfo mapHitToFileInfo(JsonNode hit) {
        try {
            String synologyPath = hit.path("SYNOMDPath").asText("");
            String fileName = hit.path("SYNOMDFSName").asText("");
            long size = hit.path("SYNOMDFSSize").asLong(0);
            long lastModifiedEpoch = hit.path("SYNOMDLastModifiedDate").asLong(0);

            if (synologyPath.isEmpty() || fileName.isEmpty()) {
                return null;
            }

            String localPath = mapToLocalPath(synologyPath);

            LocalDateTime lastModified = lastModifiedEpoch > 0
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpoch), ZoneId.systemDefault())
                    : LocalDateTime.now();

            return new FileInfo(localPath, fileName, size, lastModified, "New");
        } catch (Exception e) {
            CentralLogger.logError("Hiba a Synology találat feldolgozásakor", e);
            return null;
        }
    }

    private String mapToLocalPath(String synologyPath) {
        String pathPrefix = synologyConfig.getPathPrefix();
        String localMountPrefix = synologyConfig.getLocalMountPrefix();

        if (synologyPath.startsWith(pathPrefix)) {
            return localMountPrefix + synologyPath.substring(pathPrefix.length());
        }
        return localMountPrefix + synologyPath;
    }
}
