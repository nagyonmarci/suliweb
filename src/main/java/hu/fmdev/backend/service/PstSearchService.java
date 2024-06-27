package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.logger.CentralLogger;
import hu.fmdev.backend.repository.FileInfoRepository;
import hu.fmdev.backend.util.HashUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
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
public class PstSearchService {

    @Autowired
    private FileInfoRepository fileInfoRepository;

    public List<FileInfo> findFiles(List<String> directories, List<String> excludedDirectories) throws IOException, InterruptedException, ExecutionException, NoSuchAlgorithmException {
        Instant start = Instant.now(); // Kezdési idő mérése
        List<FileInfo> foundFiles = Collections.synchronizedList(new ArrayList<>());

        // Executor service 10 szálon való futtatáshoz
        ExecutorService executorService = Executors.newFixedThreadPool(10);

        List<Future<List<FileInfo>>> futures = new ArrayList<>();
        for (String directory : directories) {
            futures.add(executorService.submit(() -> {
                List<FileInfo> filesInDirectory = new ArrayList<>();
                CentralLogger.logInfo("Bejárás kezdete könyvtár: " + directory);
                try (Stream<Path> paths = Files.walk(Paths.get(directory))) {
                    filesInDirectory = paths
                            .filter(Files::isRegularFile)
                            .filter(file -> {
                                boolean result = !file.toString().endsWith(".tmp");
                                CentralLogger.logDebug("Fájl végződés ellenőrzése (nem .tmp): " + file + " -> " + result);
                                return result;
                            })
                            .filter(file -> {
                                boolean result = excludedDirectories.stream().noneMatch(excludedDir -> file.startsWith(excludedDir));
                                CentralLogger.logDebug("Kizárt könyvtárak ellenőrzése: " + file + " -> " + result);
                                return result;
                            })
                            .map(file -> {
                                try {
                                    String hash = HashUtil.calculateHash(file);
                                    FileInfo fileInfo = new FileInfo(
                                            file.toString(),
                                            Files.size(file),
                                            LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault()),
                                            "Új",
                                            hash);
                                    CentralLogger.logInfo("Fájl megtalálva: " + fileInfo.getPath());
                                    return fileInfo;
                                } catch (IOException | NoSuchAlgorithmException e) {
                                    CentralLogger.logError("Hiba történt a fájl olvasása közben: " + file, e);
                                    return null;
                                }
                            })
                            .filter(Objects::nonNull)
                            .collect(Collectors.toList());
                }
                return filesInDirectory;
            }));
        }

        // A jövőbeni eredmények összegyűjtése
        for (Future<List<FileInfo>> future : futures) {
            foundFiles.addAll(future.get());
        }

        executorService.shutdown();
        executorService.awaitTermination(1, TimeUnit.HOURS);

        Instant end = Instant.now(); // Befejezési idő mérése
        Duration timeElapsed = Duration.between(start, end);
        CentralLogger.logInfo("Keresés ideje: " + timeElapsed.toMillis() + " milliszekundum");
        return foundFiles;
    }

    public void saveOrUpdateFileInfo(List<FileInfo> fileInfoList, List<String> searchDirectories) {
        CentralLogger.logInfo("Starting saveOrUpdateFileInfo with " + fileInfoList.size() + " files and " + searchDirectories.size() + " search directories");
        CentralLogger.logInfo("Search directories: " + searchDirectories);

        // Gyűjtsük össze az összes megtalált fájl útvonalát
        Set<String> foundFilePaths = fileInfoList.stream()
                .map(FileInfo::getPath)
                .collect(Collectors.toSet());

        // Frissítsük vagy adjuk hozzá a fájlinformációkat
        fileInfoList.forEach(fileInfo -> {
            CentralLogger.logInfo("Fájl feldolgozása: " + fileInfo.getPath());
            Optional<FileInfo> existingFileInfoOpt = fileInfoRepository.findFirstByPath(fileInfo.getPath());
            if (existingFileInfoOpt.isPresent()) {
                FileInfo updateInfo = existingFileInfoOpt.get();
                boolean needsUpdate = !updateInfo.getLastModified().equals(fileInfo.getLastModified())
                        || updateInfo.getSize() != fileInfo.getSize()
                        || "Törölt".equals(updateInfo.getStatus());
                CentralLogger.logDebug("Frissítés szükséges: " + needsUpdate);
                if (needsUpdate) {
                    updateInfo.setLastModified(fileInfo.getLastModified());
                    updateInfo.setSize(fileInfo.getSize());
                    updateInfo.setStatus("Módosított");
                    updateInfo.setHash(fileInfo.getHash());
                    fileInfoRepository.save(updateInfo);
                    CentralLogger.logInfo("File updated: " + updateInfo.getPath());
                }
            } else {
                fileInfoRepository.save(fileInfo);
                CentralLogger.logInfo("New file saved: " + fileInfo.getPath());
            }
        });

        // Az adatbázisban lévő összes fájlinformáció ellenőrzése
        List<FileInfo> allFileInfo = fileInfoRepository.findAll();
        allFileInfo.forEach(fileInfo -> {
            boolean isInSearchDirectory = searchDirectories.stream()
                    .map(Paths::get)
                    .anyMatch(dir -> {
                        Path filePath = Paths.get(fileInfo.getPath());
                        return filePath.startsWith(dir);
                    });
            boolean isMissingInCurrentList = !foundFilePaths.contains(fileInfo.getPath());
            CentralLogger.logInfo("Checking file: " + fileInfo.getPath() + " isInSearchDirectory: " + isInSearchDirectory + " isMissingInCurrentList: " + isMissingInCurrentList);

            if (isInSearchDirectory && isMissingInCurrentList) {
                fileInfo.setStatus("Törölt");
                fileInfoRepository.save(fileInfo);
                CentralLogger.logInfo("File marked as deleted: " + fileInfo.getPath());
            }
        });

        CentralLogger.logInfo("Fájlinformációk frissítve és mentve az adatbázisban.");
    }

    public void findAndSaveFiles(List<String> directories, List<String> excludedDirectories) throws IOException, InterruptedException, ExecutionException, NoSuchAlgorithmException {
        Instant start = Instant.now(); // Kezdési idő mérése
        List<FileInfo> foundFiles = findFiles(directories, excludedDirectories);
        saveOrUpdateFileInfo(foundFiles, directories);
        Instant end = Instant.now(); // Befejezési idő mérése
        Duration timeElapsed = Duration.between(start, end);
        CentralLogger.logInfo("Keresés és mentés teljes ideje: " + timeElapsed.toMillis() + " milliszekundum");
    }
}
