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
        List<FileInfo> foundFiles = new ArrayList<>();
        for (String directory : directories) {
            try (Stream<Path> paths = Files.walk(Paths.get(directory))) {
                List<FileInfo> filesInDirectory = paths
                        .filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".pst"))
                        .filter(file -> excludedDirectories.stream().noneMatch(excludedDir -> file.toString().startsWith(excludedDir)))
                        .map(file -> {
                            try {
                                return new FileInfo(
                                        file.toString(),
                                        Files.size(file),
                                        LocalDateTime.ofInstant(Instant.ofEpochMilli(Files.getLastModifiedTime(file).toMillis()), ZoneId.systemDefault()),
                                        "Új");
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
        return foundFiles;
    }


    public void saveOrUpdateFileInfo(List<FileInfo> fileInfoList) {
        fileInfoList.forEach(fileInfo -> {
            Optional<FileInfo> existingFileInfo = fileInfoRepository.findByPath(fileInfo.getPath());
            if (existingFileInfo.isPresent()) {
                FileInfo updateInfo = existingFileInfo.get();
                boolean needsUpdate = !updateInfo.getLastModified().equals(fileInfo.getLastModified())
                        || updateInfo.getSize() != fileInfo.getSize()
                        || "Törölt".equals(updateInfo.getStatus()); // Ellenőrzés, hogy a státusz "Törölt" volt-e
                if (needsUpdate) {
                    updateInfo.setLastModified(fileInfo.getLastModified());
                    updateInfo.setSize(fileInfo.getSize());
                    updateInfo.setStatus("Módosított"); // Státusz frissítése "Módosítottra", ha a fájl korábban "Törölt" volt
                    fileInfoRepository.save(updateInfo);
                }
            } else {
                fileInfoRepository.save(fileInfo);
            }
        });

        // A "Törölt" státuszú fájlok frissítése, amelyek nem szerepelnek a jelenlegi listában
        List<FileInfo> allFileInfo = fileInfoRepository.findAll();
        allFileInfo.forEach(storedFileInfo -> {
            if (fileInfoList.stream().noneMatch(f -> f.getPath().equals(storedFileInfo.getPath()))) {
                storedFileInfo.setStatus("Törölt");
                fileInfoRepository.save(storedFileInfo);
            }
        });

        log.info("Fájlinformációk frissítve és mentve az adatbázisban.");
    }

    public void findAndSavePstFiles(List<String> directories, List<String> excludedDirectories) {
        for (String directory : directories) {
            Path startPath = Paths.get(directory);
            try (Stream<Path> paths = Files.walk(startPath)) {
                paths
                        .filter(Files::isRegularFile)
                        .filter(file -> file.toString().endsWith(".pst"))
                        .filter(file -> excludedDirectories.stream().noneMatch(excludedDir -> file.toString().startsWith(excludedDir)))
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
                                fileInfoRepository.save(fileInfo);
                                log.info("PST fájl mentve az adatbázisba: " + file);

                            } catch (IOException e) {
                                log.error("Hiba történt a fájl olvasása közben: " + file, e);
                            }
                        });
            } catch (IOException e) {
                log.error("Hiba történt a könyvtár bejárása közben: " + startPath, e);
            }
        }
    }

}