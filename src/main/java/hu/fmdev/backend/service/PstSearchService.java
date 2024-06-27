package hu.fmdev.backend.service;

import hu.fmdev.backend.domain.FileInfo;
import hu.fmdev.backend.repository.FileInfoRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@Slf4j
public class PstSearchService {

    @Autowired
    private FileInfoRepository fileInfoRepository;

    public List<FileInfo> findPstFiles(List<String> directories, List<String> excludedDirectories) throws IOException {
        Instant start = Instant.now(); // Kezdési idő mérése
        List<FileInfo> foundFiles = new ArrayList<>();
        for (String directory : directories) {
            log.info("Bejárás kezdete könyvtár: " + directory);
            try (Stream<Path> paths = Files.walk(Paths.get(directory))) {
                List<FileInfo> filesInDirectory = paths
                        .filter(Files::isRegularFile)
                        .filter(file -> {
                            boolean result = file.toString().endsWith(".pst");
                            log.debug("Fájl végződés ellenőrzése: " + file + " -> " + result);
                            return result;
                        })
                        .filter(file -> {
                            boolean result = excludedDirectories.stream().noneMatch(excludedDir -> file.startsWith(excludedDir));
                            log.debug("Kizárt könyvtárak ellenőrzése: " + file + " -> " + result);
                            return result;
                        })
                        .map(file -> {
                            try {
                                FileInfo fileInfo = new FileInfo(
                                        file.toString(),
                                        Files.size(file),
                                        LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault()),
                                        "Új");
                                log.info("Fájl megtalálva: " + fileInfo.getPath());
                                return fileInfo;
                            } catch (IOException e) {
                                log.error("Hiba történt a fájl olvasása közben: " + file, e);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                foundFiles.addAll(filesInDirectory);
            }
        }
        Instant end = Instant.now(); // Befejezési idő mérése
        Duration timeElapsed = Duration.between(start, end);
        log.info("Keresés ideje: {} milliszekundum", timeElapsed.toMillis());
        return foundFiles;
    }

    public void saveOrUpdateFileInfo(List<FileInfo> fileInfoList, List<String> searchDirectories) {
        log.info("Starting saveOrUpdateFileInfo with {} files and {} search directories", fileInfoList.size(), searchDirectories.size());
        log.info("Search directories: {}", searchDirectories);

        // Gyűjtsük össze az összes megtalált fájl útvonalát
        Set<String> foundFilePaths = fileInfoList.stream()
                .map(FileInfo::getPath)
                .collect(Collectors.toSet());

        // Frissítsük vagy adjuk hozzá a fájlinformációkat
        fileInfoList.forEach(fileInfo -> {
            log.info("Fájl feldolgozása: " + fileInfo.getPath());
            Optional<FileInfo> existingFileInfoOpt = fileInfoRepository.findFirstByPath(fileInfo.getPath());
            if (existingFileInfoOpt.isPresent()) {
                FileInfo updateInfo = existingFileInfoOpt.get();
                boolean needsUpdate = !updateInfo.getLastModified().equals(fileInfo.getLastModified())
                        || updateInfo.getSize() != fileInfo.getSize()
                        || "Törölt".equals(updateInfo.getStatus());
                log.debug("Frissítés szükséges: " + needsUpdate);
                if (needsUpdate) {
                    updateInfo.setLastModified(fileInfo.getLastModified());
                    updateInfo.setSize(fileInfo.getSize());
                    updateInfo.setStatus("Módosított");
                    fileInfoRepository.save(updateInfo);
                    log.info("File updated: " + updateInfo.getPath());
                }
            } else {
                fileInfoRepository.save(fileInfo);
                log.info("New file saved: " + fileInfo.getPath());
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
            log.info("Checking file: " + fileInfo.getPath() + " isInSearchDirectory: " + isInSearchDirectory + " isMissingInCurrentList: " + isMissingInCurrentList);

            if (isInSearchDirectory && isMissingInCurrentList) {
                fileInfo.setStatus("Törölt");
                fileInfoRepository.save(fileInfo);
                log.info("File marked as deleted: " + fileInfo.getPath());
            }
        });

        log.info("Fájlinformációk frissítve és mentve az adatbázisban.");
    }

    public void findAndSavePstFiles(List<String> directories, List<String> excludedDirectories) {
        Instant start = Instant.now(); // Kezdési idő mérése
        List<FileInfo> foundFiles = new ArrayList<>();
        for (String directory : directories) {
            Path startPath = Paths.get(directory);
            log.info("Könyvtár bejárása kezdete: " + directory);
            try (Stream<Path> paths = Files.walk(startPath)) {
                paths
                        .filter(Files::isRegularFile)
                        .filter(file -> {
                            boolean result = file.toString().endsWith(".pst");
                            log.debug("Fájl végződés ellenőrzése: " + file + " -> " + result);
                            return result;
                        })
                        .filter(file -> {
                            boolean result = excludedDirectories.stream().noneMatch(excludedDir -> file.startsWith(excludedDir));
                            log.debug("Kizárt könyvtárak ellenőrzése: " + file + " -> " + result);
                            return result;
                        })
                        .forEach(file -> {
                            try {
                                if (!Files.isReadable(file)) {
                                    log.warn("Nincs olvasási jogosultság: " + file);
                                    return;
                                }

                                FileInfo fileInfo = new FileInfo(
                                        file.toString(),
                                        Files.size(file),
                                        LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault()),
                                        "Új");

                                log.info("Fájl megtalálva: " + fileInfo.getPath());

                                // Keresés a meglévő bejegyzések között
                                Optional<FileInfo> existingFileInfoOpt = fileInfoRepository.findFirstByPath(fileInfo.getPath());
                                if (existingFileInfoOpt.isPresent()) {
                                    FileInfo existingFileInfo = existingFileInfoOpt.get();
                                    boolean needsUpdate = !existingFileInfo.getLastModified().equals(fileInfo.getLastModified())
                                            || existingFileInfo.getSize() != fileInfo.getSize()
                                            || "Törölt".equals(existingFileInfo.getStatus());
                                    if (needsUpdate) {
                                        existingFileInfo.setLastModified(fileInfo.getLastModified());
                                        existingFileInfo.setSize(fileInfo.getSize());
                                        existingFileInfo.setStatus("Módosított");
                                        fileInfoRepository.save(existingFileInfo);
                                        log.info("File updated: " + existingFileInfo.getPath());
                                    }
                                } else {
                                    fileInfoRepository.save(fileInfo);
                                    log.info("New file saved: " + fileInfo.getPath());
                                }

                                foundFiles.add(fileInfo);
                            } catch (IOException e) {
                                log.error("Hiba történt a fájl olvasása közben: " + file, e);
                            }
                        });
            } catch (IOException e) {
                log.error("Hiba történt a könyvtár bejárása közben: " + startPath, e);
            }
        }

        saveOrUpdateFileInfo(foundFiles, directories);

        Instant end = Instant.now(); // Befejezési idő mérése
        Duration timeElapsed = Duration.between(start, end);
        log.info("Keresés és mentés teljes ideje: {} milliszekundum", timeElapsed.toMillis());
    }
}
