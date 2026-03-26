package hu.fmdev.backend.service.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TextExtractionServiceTest {

    private TextExtractionService service;

    @BeforeEach
    void setUp() {
        service = new TextExtractionService();
    }

    // --- getEmailTextContent ---

    @Test
    void getEmailTextContent_prefersPlainBody() {
        String result = service.getEmailTextContent("Plain text body", "<p>HTML body</p>");
        assertEquals("Plain text body", result);
    }

    @Test
    void getEmailTextContent_fallsBackToHtml() {
        String result = service.getEmailTextContent(null, "<p>HTML content</p>");
        assertFalse(result.isBlank());
        assertTrue(result.contains("HTML content"));
    }

    @Test
    void getEmailTextContent_blankBodyFallsBackToHtml() {
        String result = service.getEmailTextContent("   ", "<b>Bold text</b>");
        assertFalse(result.isBlank());
        assertTrue(result.contains("Bold text"));
    }

    @Test
    void getEmailTextContent_bothNull_returnsEmpty() {
        assertEquals("", service.getEmailTextContent(null, null));
    }

    @Test
    void getEmailTextContent_bothBlank_returnsEmpty() {
        assertEquals("", service.getEmailTextContent("", ""));
    }

    // --- extractTextFromHtml ---

    @Test
    void extractTextFromHtml_nullInput_returnsEmpty() {
        assertEquals("", service.extractTextFromHtml(null));
    }

    @Test
    void extractTextFromHtml_blankInput_returnsEmpty() {
        assertEquals("", service.extractTextFromHtml("   "));
    }

    @Test
    void extractTextFromHtml_stripsHtmlTags() {
        String result = service.extractTextFromHtml("<p>Hello <b>World</b></p>");
        assertTrue(result.contains("Hello"));
        assertTrue(result.contains("World"));
        assertFalse(result.contains("<p>"));
        assertFalse(result.contains("<b>"));
    }

    @Test
    void extractTextFromHtml_handlesComplexHtml() {
        String html = "<html><body><h1>Title</h1><p>Paragraph with <a href='#'>link</a></p></body></html>";
        String result = service.extractTextFromHtml(html);
        assertTrue(result.contains("Title"));
        assertTrue(result.contains("Paragraph"));
        assertTrue(result.contains("link"));
    }

    @Test
    void extractTextFromHtml_trimsResult() {
        String result = service.extractTextFromHtml("<p>  text  </p>");
        assertFalse(result.startsWith(" "));
        assertFalse(result.endsWith(" "));
    }

    // --- extractTextFromFile ---

    @Test
    void extractTextFromFile_nonExistentFile_returnsEmpty() {
        String result = service.extractTextFromFile("/tmp/nonexistent_file_12345.pdf");
        assertEquals("", result);
    }

    @Test
    void extractTextFromFile_emptyPath_returnsEmpty() {
        String result = service.extractTextFromFile("");
        assertEquals("", result);
    }
}
