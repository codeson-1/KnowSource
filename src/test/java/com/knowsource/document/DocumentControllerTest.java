package com.knowsource.document;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.user.DemoUserInitializer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
class DocumentControllerTest {

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
    void ingestsDocumentAndPersistsChunks() throws Exception {
        String kbId = createKnowledgeBase("Engineering KB");

        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/documents", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Engineering Handbook",
                                  "content": "第一段介绍系统目标。第二段介绍文档解析和切块。第三段介绍后续向量化和检索。"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/documents/")))
                .andExpect(jsonPath("$.document.id").isNotEmpty())
                .andExpect(jsonPath("$.document.kbId").value(kbId))
                .andExpect(jsonPath("$.document.title").value("Engineering Handbook"))
                .andExpect(jsonPath("$.document.status").value("DRAFT"))
                .andExpect(jsonPath("$.document.indexStatus").value("NONE"))
                .andExpect(jsonPath("$.document.version").value(1))
                .andExpect(jsonPath("$.document.fileType").value("TEXT"))
                .andExpect(jsonPath("$.ingestTaskId").isNotEmpty())
                .andExpect(jsonPath("$.ingestStatus").value("READY"))
                .andExpect(jsonPath("$.parentChunkCount").value(greaterThan(0)))
                .andExpect(jsonPath("$.childChunkCount").value(greaterThan(0)))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String docId = body.path("document").path("id").asText();

        Long readyTasks = jdbcClient.sql("SELECT COUNT(*) FROM ingest_tasks WHERE doc_id = :docId AND status = 'READY'")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long parentChunks = jdbcClient.sql("SELECT COUNT(*) FROM chunk_parents WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long childChunks = jdbcClient.sql("SELECT COUNT(*) FROM chunk_children WHERE doc_id = :docId AND doc_version = 1")
                .param("docId", docId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(readyTasks).isEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(parentChunks).isGreaterThan(0);
        org.assertj.core.api.Assertions.assertThat(childChunks).isGreaterThan(0);
    }

    @Test
    void listsDocumentsByKnowledgeBase() throws Exception {
        String kbId = createKnowledgeBase("Product KB");
        createDocument(kbId, "Release Notes", "上线说明内容");

        mockMvc.perform(get("/api/kbs/{kbId}/documents", kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].title").value("Release Notes"))
                .andExpect(jsonPath("$[0].status").value("DRAFT"));
    }

    @Test
    void getsDocumentDetailAndChunks() throws Exception {
        String kbId = createKnowledgeBase("Support KB");
        String docId = createDocument(kbId, "FAQ", "问题一答案。问题二答案。问题三答案。");

        mockMvc.perform(get("/api/documents/{docId}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(docId))
                .andExpect(jsonPath("$.title").value("FAQ"));

        mockMvc.perform(get("/api/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].docId").value(docId))
                .andExpect(jsonPath("$[0].docVersion").value(1))
                .andExpect(jsonPath("$[0].parentChunkId").isNotEmpty())
                .andExpect(jsonPath("$[0].chunkType").value("TEXT"));
    }

    @Test
    void rejectsBlankDocumentContent() throws Exception {
        String kbId = createKnowledgeBase("Policy KB");

        mockMvc.perform(post("/api/kbs/{kbId}/documents", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Empty Doc",
                                  "content": "   "
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Document content is required."));
    }

    @Test
    void returnsNotFoundWhenKnowledgeBaseIsMissing() throws Exception {
        mockMvc.perform(post("/api/kbs/{kbId}/documents", "missing-kb")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "Doc",
                                  "content": "content"
                                }
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Knowledge base not found."));
    }

    private String createKnowledgeBase(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).path("id").asText();
    }

    private String createDocument(String kbId, String title, String content) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/kbs/{kbId}/documents", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "title": "%s",
                                  "content": "%s"
                                }
                                """.formatted(title, content)))
                .andExpect(status().isCreated())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString()).path("document").path("id").asText();
    }
}
