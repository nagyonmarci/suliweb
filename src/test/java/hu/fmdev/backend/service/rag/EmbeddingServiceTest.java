package hu.fmdev.backend.service.rag;

import hu.fmdev.backend.config.RagConfig;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EmbeddingServiceTest {

    private MockWebServer mockServer;
    private EmbeddingService embeddingService;

    @BeforeEach
    void setUp() throws IOException {
        mockServer = new MockWebServer();
        mockServer.start();

        RagConfig config = new RagConfig();
        config.setEmbeddingModel("nomic-embed-text");

        WebClient webClient = WebClient.builder()
                .baseUrl(mockServer.url("/").toString())
                .build();

        embeddingService = new EmbeddingService(webClient, config);
    }

    @AfterEach
    void tearDown() throws IOException {
        mockServer.shutdown();
    }

    @Test
    void embed_nullInput_returnsEmptyList() {
        List<Double> result = embeddingService.embed(null);
        assertTrue(result.isEmpty());
    }

    @Test
    void embed_blankInput_returnsEmptyList() {
        List<Double> result = embeddingService.embed("   ");
        assertTrue(result.isEmpty());
    }

    @Test
    void embed_successfulResponse_returnsEmbedding() {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"embeddings\": [[0.1, 0.2, 0.3, 0.4]]}")
                .addHeader("Content-Type", "application/json"));

        List<Double> result = embeddingService.embed("test text");
        assertEquals(4, result.size());
        assertEquals(0.1, result.get(0), 0.001);
        assertEquals(0.4, result.get(3), 0.001);
    }

    @Test
    void embed_sendsCorrectRequest() throws InterruptedException {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"embeddings\": [[0.1]]}")
                .addHeader("Content-Type", "application/json"));

        embeddingService.embed("hello world");

        RecordedRequest request = mockServer.takeRequest();
        assertEquals("/api/embed", request.getPath());
        assertEquals("POST", request.getMethod());
        String body = request.getBody().readUtf8();
        assertTrue(body.contains("nomic-embed-text"));
        assertTrue(body.contains("hello world"));
    }

    @Test
    void embed_emptyEmbeddings_returnsEmptyList() {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"embeddings\": []}")
                .addHeader("Content-Type", "application/json"));

        List<Double> result = embeddingService.embed("test");
        assertTrue(result.isEmpty());
    }

    @Test
    void embed_serverError_returnsEmptyList() {
        mockServer.enqueue(new MockResponse().setResponseCode(500));

        List<Double> result = embeddingService.embed("test");
        assertTrue(result.isEmpty());
    }

    @Test
    void embed_missingEmbeddingsKey_returnsEmptyList() {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"model\": \"nomic-embed-text\"}")
                .addHeader("Content-Type", "application/json"));

        List<Double> result = embeddingService.embed("test");
        assertTrue(result.isEmpty());
    }

    @Test
    void isAvailable_serverUp_returnsTrue() {
        mockServer.enqueue(new MockResponse()
                .setBody("{\"models\": []}")
                .addHeader("Content-Type", "application/json"));

        assertTrue(embeddingService.isAvailable());
    }

    @Test
    void isAvailable_serverDown_returnsFalse() throws IOException {
        mockServer.shutdown();
        assertFalse(embeddingService.isAvailable());
    }
}
