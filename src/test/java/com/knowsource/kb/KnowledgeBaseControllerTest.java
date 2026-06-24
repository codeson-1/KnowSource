package com.knowsource.kb;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    void rejectsBlankKnowledgeBaseName() throws Exception {
        mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Knowledge base name is required."));
    }
}
