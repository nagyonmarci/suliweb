package hu.fmdev.backend.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class HashUtil {

    public static String calculateHash(Path path) throws IOException, NoSuchAlgorithmException {
        path = path.toAbsolutePath().normalize();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (DigestInputStream dis = new DigestInputStream(new FileInputStream(path.toFile()), digest)) {
            byte[] buffer = new byte[8192];
            while (dis.read(buffer) != -1) ;
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public static String calculatePartialHash(Path path, long maxBytes) throws IOException, NoSuchAlgorithmException {
        path = path.toAbsolutePath().normalize();
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(path.toFile())) {
            byte[] buffer = new byte[8192];
            long remaining = maxBytes;
            int bytesRead;
            while (remaining > 0 && (bytesRead = fis.read(buffer, 0, (int) Math.min(buffer.length, remaining))) != -1) {
                digest.update(buffer, 0, bytesRead);
                remaining -= bytesRead;
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
