package hu.fmdev.backend.security;

import hu.fmdev.backend.config.SecurityConfig;
import hu.fmdev.backend.controller.EDiscoveryController;
import hu.fmdev.backend.controller.KnowledgeGraphController;
import hu.fmdev.backend.controller.PstProcessorController;
import hu.fmdev.backend.service.EDiscoveryIngestionService;
import hu.fmdev.backend.service.EDiscoverySearchService;
import hu.fmdev.backend.service.GraphSearchService;
import hu.fmdev.backend.service.KnowledgeGraphIngestionService;
import hu.fmdev.backend.service.PstProcessorService;
import hu.fmdev.backend.service.auth.JwtAuthenticationFilter;
import hu.fmdev.backend.service.auth.JwtTokenProvider;
import hu.fmdev.backend.service.rag.GraphRagChatService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({EDiscoveryController.class, KnowledgeGraphController.class, PstProcessorController.class})
@Import({SecurityConfig.class, JwtAuthenticationFilter.class})
@TestPropertySource(properties = {
        "jwt.secret=dGVzdC1zZWNyZXQtZm9yLXRlc3Rpbmctb25seS10aGlzLWlzLWxvbmctZW5vdWdoLXRv", // gitleaks:allow
        "cors.allowed-origins=http://localhost"
})
class SecurityFilterChainTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean EDiscoveryIngestionService ediscoveryIngestionService;
    @MockitoBean EDiscoverySearchService ediscoverySearchService;
    @MockitoBean KnowledgeGraphIngestionService kgIngestionService;
    @MockitoBean GraphSearchService graphSearchService;
    @MockitoBean GraphRagChatService graphRagChatService;
    @MockitoBean PstProcessorService pstProcessorService;

    // --- Token nélkül minden védett végpont 401-et ad ---

    @Test
    void ediscovery_ingest_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/ediscovery/ingest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void kg_ingest_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/kg/ingest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void kg_chat_noToken_returns401() throws Exception {
        mockMvc.perform(post("/api/kg/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test\",\"topK\":5,\"model\":\"llama3.2\",\"history\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pst_processFromDb_noToken_returns401() throws Exception {
        mockMvc.perform(post("/pst/processFromDb").param("saveAttachments", "false"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ediscovery_search_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/ediscovery/search").param("q", "test"))
                .andExpect(status().isUnauthorized());
    }

    // --- ROLE_USER admin végponton → 403 ---

    @Test
    void ediscovery_ingest_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/ediscovery/ingest")
                        .with(user("testuser").authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void kg_ingest_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/kg/ingest")
                        .with(user("testuser").authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void pst_processFromDb_userRole_returns403() throws Exception {
        mockMvc.perform(post("/pst/processFromDb").param("saveAttachments", "false")
                        .with(user("testuser").authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
    }

    // --- ROLE_ADMIN trigger végponton → 2xx ---

    @Test
    void ediscovery_ingest_adminRole_returnsOk() throws Exception {
        when(ediscoveryIngestionService.isRunning()).thenReturn(false);
        mockMvc.perform(post("/api/ediscovery/ingest")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    @Test
    void kg_ingest_adminRole_returnsOk() throws Exception {
        when(kgIngestionService.isRunning()).thenReturn(false);
        mockMvc.perform(post("/api/kg/ingest")
                        .with(user("admin").authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk());
    }

    // --- ROLE_USER olvasó végponton → 2xx ---

    @Test
    void ediscovery_search_userRole_returnsOk() throws Exception {
        when(ediscoverySearchService.search(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(java.util.List.of());
        mockMvc.perform(get("/api/ediscovery/search").param("q", "test")
                        .with(user("testuser").authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isOk());
    }
}
