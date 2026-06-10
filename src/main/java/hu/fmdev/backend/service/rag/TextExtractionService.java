package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.logger.CentralLogger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class TextExtractionService {

    private final Tika tika = new Tika();

    private static final int MAX_BODY_CHARS = 50_000;

    // Detects the start of a reply chain or forwarded block (cut everything from here)
    private static final Pattern REPLY_CHAIN_PATTERN = Pattern.compile(
            "(?mi)^[ \\t]*(-{3,}[ \\t]*(?:original message|eredeti üzenet|forwarded message|továbbított üzenet)[^\\n]*$" +
            "|On .{5,120}?wrote:\\s*$" +
            "|Van:[ \\t]*.+@.+" +
            "|Feladó:[ \\t]*.+" +
            "|From:[ \\t]*.+@.+)" +
            "|(?m)^--[ \\t]*$"
    );

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
     * Reply chains and RFC 3676 signatures are stripped before returning.
     */
    public String getEmailTextContent(String body, String htmlContent) {
        if (body != null && !body.isBlank()) {
            String trimmed = body.trim();
            String stripped = stripReplyChains(trimmed);
            return stripped.length() > MAX_BODY_CHARS ? stripped.substring(0, MAX_BODY_CHARS) : stripped;
        }
        if (htmlContent != null && !htmlContent.isBlank()) {
            return stripReplyChains(extractTextFromHtml(htmlContent));
        }
        return "";
    }

    /**
     * Removes reply chains and email signatures from plain text.
     * Cuts everything from the first reply/forward/signature marker onwards.
     */
    String stripReplyChains(String text) {
        if (text == null || text.isBlank()) return text;
        Matcher m = REPLY_CHAIN_PATTERN.matcher(text);
        if (m.find()) {
            String stripped = text.substring(0, m.start()).stripTrailing();
            if (!stripped.isBlank()) {
                CentralLogger.logInfo("Reply chain stripped: " + text.length() + " → " + stripped.length() + " chars");
                return stripped;
            }
        }
        return text;
    }
}
