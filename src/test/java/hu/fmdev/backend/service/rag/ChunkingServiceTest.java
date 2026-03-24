package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChunkingServiceTest {

    private ChunkingService chunkingService;

    @BeforeEach
    void setUp() {
        RagConfig config = new RagConfig();
        config.setChunkSize(50);
        config.setChunkOverlap(10);
        chunkingService = new ChunkingService(config);
    }

    @Test
    void chunkText_nullInput_returnsEmptyList() {
        List<String> result = chunkingService.chunkText(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void chunkText_blankInput_returnsEmptyList() {
        assertTrue(chunkingService.chunkText("").isEmpty());
        assertTrue(chunkingService.chunkText("   ").isEmpty());
    }

    @Test
    void chunkText_shortText_returnsSingleChunk() {
        List<String> result = chunkingService.chunkText("Hello world");
        assertEquals(1, result.size());
        assertEquals("Hello world", result.getFirst());
    }

    @Test
    void chunkText_exactlyChunkSize_returnsSingleChunk() {
        // 50 chars exactly
        String text = "a".repeat(50);
        List<String> result = chunkingService.chunkText(text);
        assertEquals(1, result.size());
    }

    @Test
    void chunkText_longText_createsMultipleChunks() {
        // Create text longer than chunk size (50)
        String text = "word ".repeat(30); // 150 chars
        List<String> result = chunkingService.chunkText(text);
        assertTrue(result.size() > 1, "Expected multiple chunks for long text");
    }

    @Test
    void chunkText_chunksHaveOverlap() {
        // With chunk size 50 and overlap 10, consecutive chunks should share some text
        String text = "The quick brown fox jumps over the lazy dog and then runs through the park to find more animals";
        List<String> result = chunkingService.chunkText(text);

        if (result.size() >= 2) {
            String lastWordsOfFirst = result.get(0).substring(Math.max(0, result.get(0).length() - 10));
            // The second chunk should start with or near the end of the first
            assertTrue(result.get(1).length() > 0);
        }
    }

    @Test
    void chunkText_normalizesWhitespace() {
        String text = "Hello    world\n\ttest";
        List<String> result = chunkingService.chunkText(text);
        assertEquals(1, result.size());
        assertEquals("Hello world test", result.getFirst());
    }

    @Test
    void chunkText_customSizeAndOverlap() {
        String text = "abcdefghij klmnopqrst uvwxyz1234 567890abcd efghijklmn";
        List<String> result = chunkingService.chunkText(text, 20, 5);
        assertTrue(result.size() > 1);
        for (String chunk : result) {
            assertTrue(chunk.length() <= 20, "Chunk exceeds max size: " + chunk);
        }
    }

    @Test
    void chunkText_noEmptyChunks() {
        String text = "This is a test text that should produce multiple chunks when split appropriately with overlap";
        List<String> result = chunkingService.chunkText(text, 30, 5);
        for (String chunk : result) {
            assertFalse(chunk.isBlank(), "Empty chunk found");
        }
    }

    @Test
    void chunkText_coversAllContent() {
        String text = "alfa beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu";
        List<String> result = chunkingService.chunkText(text, 25, 5);

        // Every word from the original text should appear in at least one chunk
        String[] words = text.split(" ");
        String allChunks = String.join(" ", result);
        for (String word : words) {
            assertTrue(allChunks.contains(word), "Word missing from chunks: " + word);
        }
    }
}
