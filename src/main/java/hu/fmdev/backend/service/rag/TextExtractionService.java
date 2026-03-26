package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.logger.CentralLogger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;

@Service
public class TextExtractionService {

    private final Tika tika = new Tika();

    // Max characters sent to the chunker per email to prevent OOM under parallel load
    private static final int MAX_BODY_CHARS = 50_000;

    /**
     * Extracts plain text from any supported file using Apache Tika.
     */
    public String extractTextFromFile(String filePath) {
        File file = new File(filePath);
        if (!file.exists() || !file.canRead()) {
            CentralLogger.logWarn("Cannot read attachment for text extraction: " + filePath);
            return "";
        }

        try {
            String text = tika.parseToString(file.toPath());
            return text != null ? text.trim() : "";
        } catch (IOException | TikaException e) {
            CentralLogger.logError("Text extraction failed for: " + filePath, e);
            return "";
        }
    }



    /**
     * Strips HTML tags and returns clean plain text from email body.
     * Uses Jsoup for memory-safe and fast O(N) HTML parsing.
     */
    public String extractTextFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        
        // Cap raw HTML BEFORE memory-intensive parsing to prevent DOM explosions on massive base64 images
        if (html.length() > MAX_BODY_CHARS * 2) {
            html = html.substring(0, MAX_BODY_CHARS * 2);
        }
        
        // Jsoup is highly optimized and immune to regex catastrophic backtracking
        String text = Jsoup.parse(html).text();

        // Hard cap to prevent memory pressure from huge emails
        if (text.length() > MAX_BODY_CHARS) {
            text = text.substring(0, MAX_BODY_CHARS);
        }
        return text;
    }

    /**
     * Gets the best available text content from an email.
     * Prefers plain text body, falls back to HTML extraction.
     */
    public String getEmailTextContent(String body, String htmlContent) {
        if (body != null && !body.isBlank()) {
            String trimmed = body.trim();
            return trimmed.length() > MAX_BODY_CHARS ? trimmed.substring(0, MAX_BODY_CHARS) : trimmed;
        }
        if (htmlContent != null && !htmlContent.isBlank()) {
            return extractTextFromHtml(htmlContent);
        }
        return "";
    }
}
