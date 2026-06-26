package com.knowsource.document;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.util.FileSystemUtils;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knowsource.user.DemoUserInitializer;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("db")
@WithMockUser(username = "demo", roles = "ADMIN")
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
        FileSystemUtils.deleteRecursively(Path.of("target/test-sources").toFile());
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
                .andExpect(jsonPath("$.ingestStatus").value("PENDING"))
                .andExpect(jsonPath("$.parentChunkCount").value(0))
                .andExpect(jsonPath("$.childChunkCount").value(0))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String docId = body.path("document").path("id").asText();
        waitForIngestReady(docId);

        mockMvc.perform(get("/api/documents/{docId}/ingest-task", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.document.id").value(docId))
                .andExpect(jsonPath("$.ingestStatus").value("READY"))
                .andExpect(jsonPath("$.parentChunkCount").value(greaterThan(0)))
                .andExpect(jsonPath("$.childChunkCount").value(greaterThan(0)));

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
                .andExpect(jsonPath("$[0].status").value("DRAFT"))
                .andExpect(jsonPath("$[0].latestIngestTaskId").isNotEmpty())
                .andExpect(jsonPath("$[0].latestIngestStatus").value("READY"))
                .andExpect(jsonPath("$[0].parentChunkCount").value(greaterThan(0)))
                .andExpect(jsonPath("$[0].childChunkCount").value(greaterThan(0)));
    }

    @Test
    void uploadsMultipartDocumentAndIngestsFromStoredSource() throws Exception {
        String kbId = createKnowledgeBase("Durable Upload KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "runbook.md",
                "text/markdown",
                """
                        # Runbook

                        The service restart process must preserve source files before background parsing.
                        Operators can retry ingestion because the worker reads from durable local storage.
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "Restart Runbook"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/documents/")))
                .andExpect(jsonPath("$.document.id").isNotEmpty())
                .andExpect(jsonPath("$.document.kbId").value(kbId))
                .andExpect(jsonPath("$.document.title").value("Restart Runbook"))
                .andExpect(jsonPath("$.document.status").value("DRAFT"))
                .andExpect(jsonPath("$.document.indexStatus").value("NONE"))
                .andExpect(jsonPath("$.document.fileType").value("MARKDOWN"))
                .andExpect(jsonPath("$.document.ossKey").value(startsWith("local://")))
                .andExpect(jsonPath("$.ingestStatus").value("PENDING"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String docId = body.path("document").path("id").asText();
        String sourceKey = body.path("document").path("ossKey").asText();
        Path storedSource = Path.of("target/test-sources").resolve(sourceKey.substring("local://".length()));
        org.assertj.core.api.Assertions.assertThat(Files.exists(storedSource)).isTrue();

        waitForIngestReady(docId);

        mockMvc.perform(get("/api/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.containsString("durable local storage")));

        mockMvc.perform(get("/api/documents/{docId}/preview", docId)
                        .param("pageNumber", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value(docId))
                .andExpect(jsonPath("$.sourceKey").value(sourceKey))
                .andExpect(jsonPath("$.previewUrl").value(org.hamcrest.Matchers.startsWith("/api/documents/source-preview?sourceKey=")))
                .andExpect(jsonPath("$.pageNumber").value(1));

        mockMvc.perform(get("/api/documents/source-preview")
                        .param("sourceKey", sourceKey))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("durable local storage")));
    }

    @Test
    void deletesDocumentCascadeDataAndStoredSource() throws Exception {
        String kbId = createKnowledgeBase("Delete Document KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "delete-me.md",
                "text/markdown",
                """
                        # Delete Me

                        This uploaded document should remove chunks and source data when deleted.
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "Delete Me"))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String docId = body.path("document").path("id").asText();
        String sourceKey = body.path("document").path("ossKey").asText();
        Path storedSource = Path.of("target/test-sources").resolve(sourceKey.substring("local://".length()));
        waitForIngestReady(docId);
        org.assertj.core.api.Assertions.assertThat(Files.exists(storedSource)).isTrue();

        mockMvc.perform(delete("/api/documents/{docId}", docId))
                .andExpect(status().isNoContent());

        Long documentRows = jdbcClient.sql("SELECT COUNT(*) FROM documents WHERE id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long taskRows = jdbcClient.sql("SELECT COUNT(*) FROM ingest_tasks WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long parentRows = jdbcClient.sql("SELECT COUNT(*) FROM chunk_parents WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long childRows = jdbcClient.sql("SELECT COUNT(*) FROM chunk_children WHERE doc_id = :docId")
                .param("docId", docId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(documentRows).isZero();
        org.assertj.core.api.Assertions.assertThat(taskRows).isZero();
        org.assertj.core.api.Assertions.assertThat(parentRows).isZero();
        org.assertj.core.api.Assertions.assertThat(childRows).isZero();
        org.assertj.core.api.Assertions.assertThat(Files.exists(storedSource)).isFalse();
    }

    @Test
    void uploadsMarkdownAndPreservesTableChunkMetadata() throws Exception {
        String kbId = createKnowledgeBase("Markdown KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "handbook.md",
                "text/markdown",
                """
                        # Benefits

                        ## Leave

                        | Type | Days |
                        | --- | --- |
                        | Annual | 10 |
                        | Sick | 5 |

                        Approval requests must be submitted before leave starts.
                        """.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "Benefits Handbook"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.fileType").value("MARKDOWN"))
                .andReturn();

        String docId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("document")
                .path("id")
                .asText();
        waitForIngestReady(docId);

        Long tableChunks = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chunk_children
                WHERE doc_id = :docId
                  AND chunk_type = 'TABLE'
                  AND content LIKE '%Benefits > Leave%'
                  AND jsonb_path_exists(metadata, '$.sectionPath[*] ? (@ == "Benefits")')
                  AND jsonb_path_exists(metadata, '$.sectionPath[*] ? (@ == "Leave")')
                  AND metadata ->> 'tableCaption' = 'Leave'
                  AND (metadata ->> 'blockIndex')::int >= 0
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(tableChunks).isPositive();

        mockMvc.perform(get("/api/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.containsString("Benefits > Leave")));
    }

    @Test
    void chunkerKeepsParagraphBoundariesAndPersistsOffsets() throws Exception {
        String kbId = createKnowledgeBase("Chunk Boundary KB");
        String firstSentence = "Approval policy sentence one contains enough explanatory detail to be useful in a retrieval chunk. ";
        String secondSentence = "Approval policy sentence two contains more explanatory detail and should stay whole. ";
        String firstParagraph = firstSentence.repeat(12) + secondSentence.repeat(8);
        String secondParagraph = "Expense policy sentence one. Expense policy sentence two.";

        String docId = createDocument(kbId, "Boundary Policy", firstParagraph + "\\n\\n" + secondParagraph);

        Long midSentenceChunks = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chunk_children
                WHERE doc_id = :docId
                  AND content NOT LIKE 'Approval policy sentence%'
                  AND content NOT LIKE 'Expense policy sentence%'
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        Long offsetChunks = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chunk_children
                WHERE doc_id = :docId
                  AND (metadata ->> 'startOffset')::int >= 0
                  AND (metadata ->> 'endOffset')::int > (metadata ->> 'startOffset')::int
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();

        org.assertj.core.api.Assertions.assertThat(midSentenceChunks).isZero();
        org.assertj.core.api.Assertions.assertThat(offsetChunks).isPositive();
    }

    @Test
    void uploadsPdfDocumentAndIngestsWithTika() throws Exception {
        String kbId = createKnowledgeBase("PDF KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "policy.pdf",
                "application/pdf",
                pdfBytes("Annual leave requires manager approval before the planned absence."));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "Leave Policy"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.fileType").value("PDF"))
                .andExpect(jsonPath("$.document.ossKey").value(startsWith("local://")))
                .andExpect(jsonPath("$.ingestStatus").value("PENDING"))
                .andReturn();

        String docId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("document")
                .path("id")
                .asText();
        waitForIngestReady(docId);

        mockMvc.perform(get("/api/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.containsString("manager approval")));
    }

    @Test
    void uploadsPdfAndPersistsPageNumbers() throws Exception {
        String kbId = createKnowledgeBase("PDF Pages KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "pages.pdf",
                "application/pdf",
                pdfBytes("First page onboarding policy.", "Second page reimbursement table."));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "Paged PDF"))
                .andExpect(status().isCreated())
                .andReturn();

        String docId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("document")
                .path("id")
                .asText();
        waitForIngestReady(docId);

        Long pageTwoChunks = jdbcClient.sql("""
                SELECT COUNT(*)
                FROM chunk_children
                WHERE doc_id = :docId AND page_number = 2 AND content LIKE '%Second page%'
                """)
                .param("docId", docId)
                .query(Long.class)
                .single();
        org.assertj.core.api.Assertions.assertThat(pageTwoChunks).isPositive();

        mockMvc.perform(get("/api/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pageNumber").value(1));
    }

    @Test
    void keepsDocumentAndMarksIngestFailedWhenPdfParsingFails() throws Exception {
        String kbId = createKnowledgeBase("Policy KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "policy.pdf",
                "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "PDF Policy"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.fileType").value("PDF"))
                .andExpect(jsonPath("$.ingestStatus").value("PENDING"))
                .andReturn();

        String docId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("document")
                .path("id")
                .asText();
        waitForIngestStatus(docId, "FAILED");

        mockMvc.perform(get("/api/documents/{docId}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(docId))
                .andExpect(jsonPath("$.fileType").value("PDF"));

        mockMvc.perform(get("/api/documents/{docId}/ingest-task", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ingestStatus").value("FAILED"))
                .andExpect(jsonPath("$.childChunkCount").value(0));
    }

    @Test
    void retriesFailedMultipartIngestFromStoredSource() throws Exception {
        String kbId = createKnowledgeBase("Retry Upload KB");
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "retry.pdf",
                "application/pdf",
                "%PDF-1.4\n%%EOF".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/kbs/{kbId}/documents/upload", kbId)
                        .file(file)
                        .param("title", "Retryable PDF"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.document.fileType").value("PDF"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String docId = body.path("document").path("id").asText();
        String sourceKey = body.path("document").path("ossKey").asText();
        waitForIngestStatus(docId, "FAILED");

        Path storedSource = Path.of("target/test-sources").resolve(sourceKey.substring("local://".length()));
        Files.write(storedSource, pdfBytes("Retry after parser failure should rebuild chunks."));

        mockMvc.perform(post("/api/documents/{docId}/ingest-task/retry", docId))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.document.id").value(docId))
                .andExpect(jsonPath("$.ingestStatus").value("PENDING"))
                .andExpect(jsonPath("$.childChunkCount").value(0));

        waitForIngestReady(docId);

        mockMvc.perform(get("/api/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(greaterThan(0))))
                .andExpect(jsonPath("$[0].content").value(org.hamcrest.Matchers.containsString("rebuild chunks")));
    }

    @Test
    void rejectsRetryForReadyIngestTask() throws Exception {
        String kbId = createKnowledgeBase("Ready Retry KB");
        String docId = createDocument(kbId, "FAQ", "Retry should be rejected for ready content.");

        mockMvc.perform(post("/api/documents/{docId}/ingest-task/retry", docId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only FAILED ingest tasks can be retried."));
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

        String docId = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("document")
                .path("id")
                .asText();
        waitForIngestReady(docId);
        return docId;
    }

    private void waitForIngestReady(String docId) throws InterruptedException {
        waitForIngestStatus(docId, "READY");
    }

    private void waitForIngestStatus(String docId, String expectedStatus) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            String status = jdbcClient.sql("""
                    SELECT status
                    FROM ingest_tasks
                    WHERE doc_id = :docId
                    ORDER BY created_at DESC, id DESC
                    LIMIT 1
                    """)
                    .param("docId", docId)
                    .query(String.class)
                    .single();
            if (expectedStatus.equals(status)) {
                return;
            }
            Thread.sleep(50);
        }
        org.assertj.core.api.Assertions.fail("Timed out waiting for ingest task " + expectedStatus + " for " + docId);
    }

    private byte[] pdfBytes(String text) throws Exception {
        return pdfBytes(new String[] { text });
    }

    private byte[] pdfBytes(String... pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (String pageText : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                    contentStream.beginText();
                    contentStream.setFont(PDType1Font.HELVETICA, 12);
                    contentStream.newLineAtOffset(72, 720);
                    contentStream.showText(pageText);
                    contentStream.endText();
                }
            }
            try (var output = new java.io.ByteArrayOutputStream()) {
                document.save(output);
                return output.toByteArray();
            }
        }
    }

}
