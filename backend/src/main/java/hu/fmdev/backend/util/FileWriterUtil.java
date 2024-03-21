package hu.fmdev.backend.util;

import hu.fmdev.backend.domain.FileInfo;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

@Component
public class FileWriterUtil {

    public void writeFileInfoToFile(List<FileInfo> fileInfos, String fileName) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(fileName))) {
            for (FileInfo fileInfo : fileInfos) {
                writer.write(fileInfo.toString());
                writer.newLine();
            }
        }
    }
}