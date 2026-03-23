package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.logger.CentralLogger;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

@Service
public class TextExtractionService {

    private final Tika tika = new Tika();

    /**
     * Extracts plain text from any supported file using Apache Tika.
     * Supports PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, images (OCR if configured), etc.
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
     * Falls back to Tika HTML parsing for complex HTML.
     */
    public String extractTextFromHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }

        try {
            String text = tika.parseToString(
                    new java.io.ByteArrayInputStream(html.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    new org.apache.tika.metadata.Metadata());
            return text != null ? text.trim() : "";
        } catch (IOException | TikaException e) {
            CentralLogger.logError("HTML text extraction failed", e);
            return html.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").trim();
        }
    }

    /**
     * Gets the best available text content from an email.
     * Prefers plain text body, falls back to HTML extraction.
     */
    public String getEmailTextContent(String body, String htmlContent) {
        if (body != null && !body.isBlank()) {
            return body.trim();
        }
        if (htmlContent != null && !htmlContent.isBlank()) {
            return extractTextFromHtml(htmlContent);
        }
        return "";
    }
}
