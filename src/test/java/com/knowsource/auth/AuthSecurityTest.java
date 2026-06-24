package com.knowsource.auth;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.user.DemoUserInitializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
class AuthSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private DemoUserInitializer demoUserInitializer;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanBusinessData() {
        jdbcClient.sql("DELETE FROM qa_traces").update();
        jdbcClient.sql("DELETE FROM chat_messages").update();
        jdbcClient.sql("DELETE FROM chat_sessions").update();
        jdbcClient.sql("DELETE FROM document_publish_events").update();
        jdbcClient.sql("DELETE FROM vector_store").update();
        jdbcClient.sql("DELETE FROM chunk_children").update();
        jdbcClient.sql("DELETE FROM chunk_parents").update();
        jdbcClient.sql("DELETE FROM ingest_tasks").update();
        jdbcClient.sql("DELETE FROM documents").update();
        jdbcClient.sql("DELETE FROM kb_members").update();
        jdbcClient.sql("DELETE FROM knowledge_bases").update();
        jdbcClient.sql("DELETE FROM refresh_tokens").update();
        jdbcClient.sql("DELETE FROM users").update();
        demoUserInitializer.run(null);
    }

    @Test
    void loginRefreshAndLogoutUseHashedRefreshTokens() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "demo",
                                  "password": "demo"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(not("")))
                .andExpect(jsonPath("$.refreshToken").value(not("")))
                .andExpect(jsonPath("$.username").value("demo"))
                .andExpect(jsonPath("$.globalRole").value("ADMIN"))
                .andReturn();

        String refreshToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("refreshToken")
                .asText();

        Long rawTokenRows = jdbcClient.sql("SELECT COUNT(*) FROM refresh_tokens WHERE token_hash = :token")
                .param("token", refreshToken)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(rawTokenRows).isZero();

        MvcResult refreshed = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value(not("")))
                .andExpect(jsonPath("$.refreshToken").value(not(refreshToken)))
                .andReturn();

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isUnauthorized());

        String rotatedRefreshToken = objectMapper.readTree(refreshed.getResponse().getContentAsString())
                .path("refreshToken")
                .asText();
        mockMvc.perform(post("/api/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rotatedRefreshToken + "\"}"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rotatedRefreshToken + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApisRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/kbs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanReadMemberKnowledgeBaseButCannotWrite() throws Exception {
        String adminToken = login("demo", "demo");
        String kbId = createKnowledgeBase(adminToken, "RBAC KB");
        long viewerId = createUser("viewer", "VIEWER");
        jdbcClient.sql("""
                INSERT INTO kb_members (kb_id, user_id, role)
                VALUES (:kbId, :userId, 'VIEWER')
                """)
                .param("kbId", kbId)
                .param("userId", viewerId)
                .update();

        String viewerToken = login("viewer", "viewer");

        mockMvc.perform(get("/api/kbs")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(kbId));

        mockMvc.perform(post("/api/kbs/{kbId}/documents", kbId)
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Viewer Doc",
                                  "content": "viewer should not write"
                                }
                                """))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Write access is required."));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "%s"
                                }
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("accessToken").asText();
    }

    private String createKnowledgeBase(String accessToken, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/kbs/")))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private long createUser(String username, String globalRole) {
        return jdbcClient.sql("""
                INSERT INTO users (username, password_hash, email, global_role)
                VALUES (:username, '{noop}' || :password, :email, :globalRole)
                RETURNING id
                """)
                .param("username", username)
                .param("password", username)
                .param("email", username + "@knowsource.local")
                .param("globalRole", globalRole)
                .query(Long.class)
                .single();
    }
}
