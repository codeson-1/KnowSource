package com.knowsource.kb;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.user.DemoUserInitializer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@WithMockUser(username = "demo", roles = "ADMIN")
class KnowledgeBaseControllerTest {

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
    void createsDemoUserWhenUsersTableIsEmpty() {
        Long userCount = jdbcClient.sql("SELECT COUNT(*) FROM users WHERE username = 'demo'")
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(userCount).isEqualTo(1);
    }

    @Test
    void createsKnowledgeBaseAndOwnerMembership() throws Exception {
        mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Engineering Handbook",
                                  "description": "Internal docs"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.startsWith("/api/kbs/")))
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.name").value("Engineering Handbook"))
                .andExpect(jsonPath("$.description").value("Internal docs"))
                .andExpect(jsonPath("$.ownerId").isNumber());

        Long ownerMemberships = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members member
                JOIN users u ON u.id = member.user_id
                WHERE u.username = 'demo' AND member.role = 'OWNER'
                """)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(ownerMemberships).isEqualTo(1);
    }

    @Test
    void listsKnowledgeBasesForDemoUser() throws Exception {
        mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Product KB\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/kbs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("Product KB"));
    }

    @Test
    void updatesKnowledgeBaseAndManagesMembers() throws Exception {
        String kbId = createKnowledgeBase("Team KB");
        long viewerId = createUser("viewer", "VIEWER");

        mockMvc.perform(put("/api/kbs/{kbId}", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "Updated Team KB",
                                  "description": "Updated docs"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Team KB"))
                .andExpect(jsonPath("$.description").value("Updated docs"));

        mockMvc.perform(post("/api/kbs/{kbId}/members", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "viewer",
                                  "role": "VIEWER"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(viewerId))
                .andExpect(jsonPath("$.memberRole").value("VIEWER"));

        mockMvc.perform(put("/api/kbs/{kbId}/members/{userId}", kbId, viewerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"EDITOR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberRole").value("EDITOR"));

        mockMvc.perform(get("/api/kbs/{kbId}/members", kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));

        mockMvc.perform(delete("/api/kbs/{kbId}/members/{userId}", kbId, viewerId))
                .andExpect(status().isNoContent());

        Long viewerMemberships = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM kb_members
                WHERE kb_id = :kbId AND user_id = :userId
                """)
                .param("kbId", kbId)
                .param("userId", viewerId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(viewerMemberships).isZero();
    }

    @Test
    void preventsRemovingLastOwner() throws Exception {
        String kbId = createKnowledgeBase("Protected KB");
        long demoUserId = jdbcClient.sql("SELECT id FROM users WHERE username = 'demo'")
                .query(Long.class)
                .single();

        mockMvc.perform(delete("/api/kbs/{kbId}/members/{userId}", kbId, demoUserId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Knowledge base must keep at least one OWNER."));
    }

    @Test
    void deletesKnowledgeBaseCascadeData() throws Exception {
        String kbId = createKnowledgeBase("Delete KB");

        mockMvc.perform(delete("/api/kbs/{kbId}", kbId))
                .andExpect(status().isNoContent());

        Long kbRows = jdbcClient.sql("SELECT COUNT(*) FROM knowledge_bases WHERE id = :kbId")
                .param("kbId", kbId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(kbRows).isZero();
    }

    @Test
    void rejectsBlankKnowledgeBaseName() throws Exception {
        mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Knowledge base name is required."));
    }

    private String createKnowledgeBase(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
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
