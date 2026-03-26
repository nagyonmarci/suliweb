package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ChunkingService {

    private final RagConfig ragConfig;

    public ChunkingService(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    /**
     * Splits text into overlapping chunks at word boundaries.
     * Uses configured chunk size and overlap (in characters).
     */
    public List<String> chunkText(String text) {
        return chunkText(text, ragConfig.getChunkSize(), ragConfig.getChunkOverlap());
    }

    public List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return chunks;
        }

        String normalized = text.replaceAll("\\s+", " ").trim();

        if (normalized.length() <= chunkSize) {
            chunks.add(normalized);
            return chunks;
        }

        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + chunkSize, normalized.length());

            // Try to break at a word boundary (look back from end)
            if (end < normalized.length()) {
                int lastSpace = normalized.lastIndexOf(' ', end);
                if (lastSpace > start) {
                    end = lastSpace;
                }
            }

            String chunk = normalized.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // Move forward by (chunkSize - overlap)
            int prevStart = start;
            start = end - overlap;
            
            // Critical fix: If a word is longer than (chunkSize - overlap), the new start 
            // might go backwards or stay the same. Force it to move forward to prevent infinite loops.
            if (start <= prevStart) {
                start = end;
            }
            
            if (start < 0) {
                start = 0;
            }
            // Avoid infinite loop
            if (start >= normalized.length()) {
                break;
            }
        }

        return chunks;
    }
}
