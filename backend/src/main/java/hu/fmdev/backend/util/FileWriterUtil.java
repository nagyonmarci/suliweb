package hu.fmdev.backend.util;

import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

@Component
public class FileWriterUtil {

    public void writePathsToFile(List<String> paths, String fileName) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for (String path : paths) {
                writer.write(path);
                writer.newLine();
            }
        }
    }
}