package hu.fmdev.backend.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

class HashUtilTest {

    @TempDir
    Path tempDir;

    @Test
    void calculateHash_knownContent_returnsExpectedSha256() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "hello world");

        String hash = HashUtil.calculateHash(file);

        // SHA-256 of "hello world"
        assertEquals("b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9", hash);
    }

    @Test
    void calculateHash_emptyFile_returnsEmptyFileHash() throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        String hash = HashUtil.calculateHash(file);

        // SHA-256 of empty string
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", hash);
    }

    @Test
    void calculateHash_sameContent_returnsSameHash() throws Exception {
        Path file1 = tempDir.resolve("file1.txt");
        Path file2 = tempDir.resolve("file2.txt");
        Files.writeString(file1, "identical content");
        Files.writeString(file2, "identical content");

        assertEquals(HashUtil.calculateHash(file1), HashUtil.calculateHash(file2));
    }

    @Test
    void calculateHash_differentContent_returnsDifferentHash() throws Exception {
        Path file1 = tempDir.resolve("a.txt");
        Path file2 = tempDir.resolve("b.txt");
        Files.writeString(file1, "content A");
        Files.writeString(file2, "content B");

        assertNotEquals(HashUtil.calculateHash(file1), HashUtil.calculateHash(file2));
    }

    @Test
    void calculateHash_returnsLowercaseHex() throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "test");

        String hash = HashUtil.calculateHash(file);

        assertTrue(hash.matches("[0-9a-f]{64}"), "Hash should be 64 lowercase hex characters");
    }

    @Test
    void calculateHash_largeFile_completesSuccessfully() throws Exception {
        Path file = tempDir.resolve("large.bin");
        byte[] data = new byte[1024 * 1024]; // 1MB
        Files.write(file, data);

        String hash = HashUtil.calculateHash(file);

        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void calculateHash_nonExistentFile_throwsIOException() {
        Path nonExistent = tempDir.resolve("missing.txt");
        assertThrows(IOException.class, () -> HashUtil.calculateHash(nonExistent));
    }

    @Test
    void calculateHash_binaryContent_works() throws Exception {
        Path file = tempDir.resolve("binary.dat");
        Files.write(file, new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xFE});

        String hash = HashUtil.calculateHash(file);

        assertNotNull(hash);
        assertEquals(64, hash.length());
    }
}
