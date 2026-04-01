package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.util.HashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
@RequiredArgsConstructor
public class PstFinderService {

    private final FileInfoRepository fileInfoRepository;

    public List<FileInfo> findFiles(List<String> directories, List<String> excludedDirectories) throws IOException, InterruptedException, ExecutionException {
        Instant start = Instant.now();
        List<FileInfo> foundFiles = Collections.synchronizedList(new ArrayList<>());

        ExecutorService executorService = Executors.newFixedThreadPool(10);

        List<Future<List<FileInfo>>> futures = directories.stream()
                .map(directory -> executorService.submit(() -> searchDirectory(directory, excludedDirectories)))
                .collect(Collectors.toList());

        for (Future<List<FileInfo>> future : futures) {
            foundFiles.addAll(future.get());
        }

        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.HOURS);

        Instant end = Instant.now();
        Duration timeElapsed = Duration.between(start, end);
        CentralLogger.logInfo("Keresés ideje: " + timeElapsed.toMillis() + " milliszekundum");
        return foundFiles;
    }

    private List<FileInfo> searchDirectory(String directory, List<String> excludedDirectories) {
        List<FileInfo> filesInDirectory = new ArrayList<>();
        CentralLogger.logInfo("Bejárás kezdete könyvtár: " + directory);
        try (Stream<Path> paths = Files.walk(Paths.get(directory))) {
            filesInDirectory = paths
                    .filter(Files::isRegularFile)
                    .filter(file -> isPstFile(file) && isNotExcluded(file, excludedDirectories))
                    .map(this::createFileInfo)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            CentralLogger.logError("Hiba történt a könyvtár bejárása közben: " + directory, e);
        }
        return filesInDirectory;
    }

    private boolean isPstFile(Path file) {
        boolean result = file.toString().endsWith(".pst");
        log.debug("Fájl végződés ellenőrzése: " + file + " -> " + result);
        return result;
    }

    private boolean isNotExcluded(Path file, List<String> excludedDirectories) {
        boolean result = excludedDirectories.stream().noneMatch(excludedDir -> file.startsWith(excludedDir));
        CentralLogger.logDebug("Kizárt könyvtárak ellenőrzése: " + file + " -> " + result);
        return result;
    }

    private FileInfo createFileInfo(Path file) {
        try {
            FileInfo fi = new FileInfo(
                    file.toString(),
                    file.getFileName().toString(),
                    Files.size(file),
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault()),
                    "New"
            );
            try {
                fi.setContentHash(HashUtil.calculatePartialHash(file, 1_048_576));
            } catch (Exception e) {
                CentralLogger.logError("Hash számítás sikertelen: " + file, e);
            }
            return fi;
        } catch (IOException e) {
            CentralLogger.logError("Hiba történt a fájl olvasása közben: " + file, e);
            return null;
        }
    }

    public void saveOrUpdateFileInfos(List<FileInfo> fileInfoList, List<String> searchDirectories) {
        CentralLogger.logInfo("Starting saveOrUpdateFileInfos with " + fileInfoList.size() + " files and " + searchDirectories.size() + " search directories");
        CentralLogger.logInfo("Search directories: " + searchDirectories);

        Set<String> foundFilePaths = fileInfoList.stream()
                .map(FileInfo::getPath)
                .collect(Collectors.toSet());

        fileInfoList.forEach(this::saveOrUpdateFile);

        List<FileInfo> allFileInfo = fileInfoRepository.findAll();
        allFileInfo.forEach(fileInfo -> checkAndMarkDeletedFiles(fileInfo, searchDirectories, foundFilePaths));

        CentralLogger.logInfo("Fájlinformációk frissítve és mentve az adatbázisban.");
    }

    private void saveOrUpdateFile(FileInfo fileInfo) {
        CentralLogger.logDebug("Fájl feldolgozása: " + fileInfo.getPath());
        Optional<FileInfo> existingFileInfoOpt = fileInfoRepository.findFirstByPath(fileInfo.getPath());
        if (existingFileInfoOpt.isPresent()) {
            FileInfo existingFileInfo = existingFileInfoOpt.get();
            if (!existingFileInfo.getLastModified().equals(fileInfo.getLastModified())
                    || existingFileInfo.getSize() != fileInfo.getSize()
                    || "Deleted".equals(existingFileInfo.getStatus())) {
                existingFileInfo.setLastModified(fileInfo.getLastModified());
                existingFileInfo.setSize(fileInfo.getSize());
                existingFileInfo.setContentHash(fileInfo.getContentHash());
                existingFileInfo.setStatus("Modified");
                fileInfoRepository.save(existingFileInfo);
                CentralLogger.logInfo("File updated: " + existingFileInfo.getPath());
            }
        } else {
            fileInfoRepository.save(fileInfo);
            CentralLogger.logInfo("New file saved: " + fileInfo.getPath());
        }
    }

    private void checkAndMarkDeletedFiles(FileInfo fileInfo, List<String> searchDirectories, Set<String> foundFilePaths) {
        boolean isInSearchDirectory = searchDirectories.stream()
                .map(Paths::get)
                .anyMatch(dir -> Paths.get(fileInfo.getPath()).startsWith(dir));
        boolean isMissingInCurrentList = !foundFilePaths.contains(fileInfo.getPath());
        CentralLogger.logDebug("Checking file: " + fileInfo.getPath() + " isInSearchDirectory: " + isInSearchDirectory + " isMissingInCurrentList: " + isMissingInCurrentList);

        if (isInSearchDirectory && isMissingInCurrentList) {
            fileInfo.setStatus("Deleted");
            fileInfoRepository.save(fileInfo);
            CentralLogger.logInfo("File marked as deleted: " + fileInfo.getPath());
        }
    }

    public void findAndSaveFiles(List<String> directories, List<String> excludedDirectories) throws IOException, InterruptedException, ExecutionException {
        Instant start = Instant.now();
        List<FileInfo> foundFiles = findFiles(directories, excludedDirectories);
        saveOrUpdateFileInfos(foundFiles, directories);
        Instant end = Instant.now();
        Duration timeElapsed = Duration.between(start, end);
        CentralLogger.logInfo("Keresés és mentés teljes ideje: " + timeElapsed.toMillis() + " milliszekundum");
    }

    public void updateDatabaseFileRecords() {
        Instant start = Instant.now();
        List<FileInfo> allFileInfo = fileInfoRepository.findAll();

        for (FileInfo fileInfo : allFileInfo) {
            updateFileInfo(fileInfo);
        }

        Instant end = Instant.now();
        Duration timeElapsed = Duration.between(start, end);
        CentralLogger.logInfo("Adatbázis fájlrekordok frissítési ideje: " + timeElapsed.toMillis() + " milliszekundum");
    }

    private void updateFileInfo(FileInfo fileInfo) {
        CentralLogger.logInfo("Fájl rekord frissítése az adatbázisban: " + fileInfo.getPath());
        try {
            Path filePath = Paths.get(fileInfo.getPath());
            if (Files.exists(filePath)) {
                long fileSize = Files.size(filePath);
                LocalDateTime lastModified = LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(filePath).toMillis()), ZoneId.systemDefault());

                if (!fileInfo.getLastModified().equals(lastModified) || fileInfo.getSize() != fileSize) {
                    fileInfo.setLastModified(lastModified);
                    fileInfo.setSize(fileSize);
                    fileInfo.setStatus("Modified");
                    fileInfoRepository.save(fileInfo);
                    CentralLogger.logInfo("File updated: " + fileInfo.getPath());
                }
            } else {
                fileInfo.setStatus("Deleted");
                fileInfoRepository.save(fileInfo);
                CentralLogger.logInfo("File marked as deleted: " + fileInfo.getPath());
            }
        } catch (IOException e) {
            CentralLogger.logError("Hiba történt a fájl olvasása közben: " + fileInfo.getPath(), e);
        }
    }

}

