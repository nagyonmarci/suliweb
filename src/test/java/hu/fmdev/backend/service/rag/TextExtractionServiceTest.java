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

    // --- stripReplyChains ---

    @Test
    void stripReplyChains_removesEnglishReplyQuote() {
        String body = "Thanks for the update.\n\nOn Mon, 12 Jan 2026, John wrote:\n> Original content here";
        String result = service.stripReplyChains(body);
        assertEquals("Thanks for the update.", result);
    }

    @Test
    void stripReplyChains_removesOriginalMessageSeparator() {
        String body = "See below.\n\n-----Original Message-----\nFrom: someone@example.com";
        String result = service.stripReplyChains(body);
        assertEquals("See below.", result);
    }

    @Test
    void stripReplyChains_removesHungarianSender() {
        String body = "Rendben.\n\nVan: kovacs.gabor@pelda.hu\nTárgy: Re: Szerződés";
        String result = service.stripReplyChains(body);
        assertEquals("Rendben.", result);
    }

    @Test
    void stripReplyChains_removesRfc3676Signature() {
        String body = "Best regards,\nJohn\n--\nJohn Doe | Company Inc.";
        String result = service.stripReplyChains(body);
        assertEquals("Best regards,\nJohn", result);
    }

    @Test
    void stripReplyChains_noMarker_returnsOriginal() {
        String body = "Simple email without any reply chain.";
        String result = service.stripReplyChains(body);
        assertEquals(body, result);
    }

    @Test
    void getEmailTextContent_stripsReplyChainFromBody() {
        String body = "Main content.\n\nOn 1 Jan 2026, boss@example.com wrote:\n> Old content";
        String result = service.getEmailTextContent(body, null);
        assertEquals("Main content.", result);
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
