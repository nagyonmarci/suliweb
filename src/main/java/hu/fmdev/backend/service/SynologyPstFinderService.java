package hu.fmdev.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.util.HashUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SynologyPstFinderService {

    private final SynologyApiClient synologyApiClient;
    private final SynologySettingsService settingsService;
    private final FileInfoRepository fileInfoRepository;
    private final PstFinderService pstFinderService;

    public List<FileInfo> findPstFilesOnNas() {
        List<FileInfo> allFiles = new ArrayList<>();
        String[] extensions = settingsService.getEffectiveSearchExtensions().split(",");

        synologyApiClient.login();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = Arrays.stream(extensions)
                    .map(String::trim)
                    .map(ext -> executor.<List<FileInfo>>submit(() -> searchExtension(ext)))
                    .toList();

            for (var future : futures) {
                try {
                    allFiles.addAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Synology keresés megszakadt", e);
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) throw re;
                    throw new RuntimeException(cause);
                }
            }
        } finally {
            synologyApiClient.logout();
        }

        CentralLogger.logInfo("Synology keresés összesen: " + allFiles.size() + " fájl.");
        return allFiles;
    }

    public record SaveResult(int found, int saved, int duplicates) {}

    public SaveResult findAndSaveFiles() {
        List<FileInfo> files = findPstFilesOnNas();
        if (files.isEmpty()) {
            return new SaveResult(0, 0, 0);
        }

        // Duplikátumok a találatok között (azonos hash → csak az első egyedi)
        Set<String> seenHashes = new java.util.HashSet<>();
        Set<String> existingPaths = fileInfoRepository.findAll().stream()
                .map(FileInfo::getPath).collect(Collectors.toSet());
        Set<String> existingHashes = fileInfoRepository.findAll().stream()
                .map(FileInfo::getContentHash).filter(h -> h != null).collect(Collectors.toSet());

        long duplicates = files.stream()
                .filter(f -> !existingPaths.contains(f.getPath())) // csak valóban új path-ok
                .filter(f -> {
                    if (f.getContentHash() == null) return false;
                    if (existingHashes.contains(f.getContentHash())) return true; // DB-ben már van
                    return !seenHashes.add(f.getContentHash()); // batch-en belüli dupli
                })
                .count();

        pstFinderService.saveOrUpdateFileInfos(files, List.of(settingsService.getEffectiveLocalMountPrefix()));

        int saved = (int) (files.stream().filter(f -> !existingPaths.contains(f.getPath())).count() - duplicates);
        CentralLogger.logInfo("Synology mentés kész: " + saved + " mentve, " + duplicates + " duplikátum kihagyva.");
        return new SaveResult(files.size(), saved, (int) duplicates);
    }

    private List<FileInfo> searchExtension(String ext) {
        CentralLogger.logInfo("Synology keresés indítása: " + ext);
        List<JsonNode> hits = synologyApiClient.searchFiles(ext);
        List<FileInfo> results = new ArrayList<>();
        for (JsonNode hit : hits) {
            FileInfo fileInfo = mapHitToFileInfo(hit);
            if (fileInfo != null) {
                results.add(fileInfo);
            }
        }
        CentralLogger.logInfo(ext + " keresés kész, találatok: " + hits.size());
        return results;
    }

    private FileInfo mapHitToFileInfo(JsonNode hit) {
        try {
            String synologyPath = hit.path("SYNOMDPath").asText("");
            String fileName = hit.path("SYNOMDFSName").asText("");
            long size = hit.path("SYNOMDFSSize").asLong(0);
            long lastModifiedEpoch = hit.path("SYNOMDLastModifiedDate").asLong(0);
            if (lastModifiedEpoch == 0) {
                lastModifiedEpoch = hit.path("SYNOMDContentModificationDate").asLong(0);
            }

            if (synologyPath.isEmpty() || fileName.isEmpty()) {
                return null;
            }

            String localPath = mapToLocalPath(synologyPath);

            LocalDateTime lastModified = lastModifiedEpoch > 0
                    ? LocalDateTime.ofInstant(Instant.ofEpochSecond(lastModifiedEpoch), ZoneId.systemDefault())
                    : LocalDateTime.now();

            FileInfo fi = new FileInfo(localPath, fileName, size, lastModified, "New");
            var path = Paths.get(localPath);
            if (Files.exists(path)) {
                Exception lastEx = null;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        fi.setContentHash(HashUtil.calculatePartialHash(path, 1_048_576));
                        lastEx = null;
                        break;
                    } catch (java.io.IOException e) {
                        lastEx = e;
                        if (attempt < 3) {
                            try { Thread.sleep(500); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                        }
                    } catch (Exception e) {
                        lastEx = e;
                        break;
                    }
                }
                if (lastEx != null) {
                    CentralLogger.logError("Hash számítás sikertelen (Synology): " + localPath, lastEx);
                }
            }
            return fi;
        } catch (Exception e) {
            CentralLogger.logError("Hiba a Synology találat feldolgozásakor", e);
            return null;
        }
    }

    private String mapToLocalPath(String synologyPath) {
        String pathPrefix = settingsService.getEffectivePathPrefix();
        String localMountPrefix = settingsService.getEffectiveLocalMountPrefix();

        if (synologyPath.startsWith(pathPrefix)) {
            return localMountPrefix + synologyPath.substring(pathPrefix.length());
        }
        return localMountPrefix + synologyPath;
    }
}
