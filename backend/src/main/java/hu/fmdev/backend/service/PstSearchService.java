package hu.fmdev.backend.service;

import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
public class PstSearchService {

    public List<String> findPstFiles(String directory) throws IOException {
        try (Stream<File> files = Files.walk(Paths.get(directory))
                .filter(Files::isRegularFile)
                .map(path -> path.toFile())
                .filter(file -> file.getName().endsWith(".pst"))) {
            return files.map(File::getAbsolutePath).collect(Collectors.toList());
        }
    }
}