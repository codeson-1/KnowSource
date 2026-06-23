package com.knowsource.index;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;

@Service
public class VectorIndexService {

    private final JdbcClient jdbcClient;
    private final ObjectProvider<DocumentEmbeddingGateway> embeddingGatewayProvider;
    private final TransactionTemplate transactionTemplate;
    private final int embeddingBatchSize;

    public VectorIndexService(
            JdbcClient jdbcClient,
            ObjectProvider<DocumentEmbeddingGateway> embeddingGatewayProvider,
            TransactionTemplate transactionTemplate,
            @Value("${knowsource.index.embedding-batch-size:16}") int embeddingBatchSize) {
        this.jdbcClient = jdbcClient;
        this.embeddingGatewayProvider = embeddingGatewayProvider;
        this.transactionTemplate = transactionTemplate;
        this.embeddingBatchSize = normalizeBatchSize(embeddingBatchSize);
    }

    public boolean hasIndexingBackend() {
        return embeddingGatewayProvider.getIfAvailable() != null;
    }

    public int reindexPublishedDocument(String kbId, String docId, int docVersion) {
        DocumentEmbeddingGateway embeddingGateway = embeddingGatewayProvider.getIfAvailable();
        if (embeddingGateway == null) {
            throw new IllegalStateException("Document embedding gateway is not available.");
        }

        List<ChunkForIndex> chunks = loadChunks(docId, docVersion);
        if (CollectionUtils.isEmpty(chunks)) {
            throw new IllegalStateException("Document has no chunks to index.");
        }

        List<float[]> embeddings = embedInBatches(embeddingGateway, chunks);

        if (embeddings.size() != chunks.size()) {
            throw new IllegalStateException("Embedding result count does not match chunk count.");
        }

        return transactionTemplate.execute(status -> {
            deleteDocumentVectors(docId);

            for (int i = 0; i < chunks.size(); i++) {
                ChunkForIndex chunk = chunks.get(i);
                jdbcClient.sql("""
                        INSERT INTO vector_store (id, content, metadata, embedding, kb_id, doc_id, status, doc_version)
                        VALUES (CAST(:id AS uuid), :content, CAST(:metadata AS jsonb), CAST(:embedding AS vector),
                                :kbId, :docId, 'published', :docVersion)
                        """)
                        .param("id", UUID.randomUUID().toString())
                        .param("content", chunk.content())
                        .param("metadata", metadataJson(kbId, chunk))
                        .param("embedding", vectorLiteral(embeddings.get(i)))
                        .param("kbId", kbId)
                        .param("docId", docId)
                        .param("docVersion", docVersion)
                        .update();
            }

            return chunks.size();
        });
    }

    public int deleteDocumentVectors(String docId) {
        return jdbcClient.sql("DELETE FROM vector_store WHERE doc_id = :docId")
                .param("docId", docId)
                .update();
    }

    private List<float[]> embedInBatches(DocumentEmbeddingGateway embeddingGateway, List<ChunkForIndex> chunks) {
        List<float[]> embeddings = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += embeddingBatchSize) {
            int end = Math.min(start + embeddingBatchSize, chunks.size());
            List<String> batchTexts = chunks.subList(start, end).stream()
                    .map(ChunkForIndex::content)
                    .toList();
            List<float[]> batchEmbeddings = embeddingGateway.embedDocuments(batchTexts);
            if (batchEmbeddings.size() != batchTexts.size()) {
                throw new IllegalStateException("Embedding result count does not match chunk count.");
            }
            embeddings.addAll(batchEmbeddings);
        }
        return embeddings;
    }

    private List<ChunkForIndex> loadChunks(String docId, int docVersion) {
        return jdbcClient.sql("""
                SELECT id, doc_id, doc_version, parent_chunk_id, content, chunk_index, page_number, chunk_type
                FROM chunk_children
                WHERE doc_id = :docId AND doc_version = :docVersion
                ORDER BY chunk_index ASC
                """)
                .param("docId", docId)
                .param("docVersion", docVersion)
                .query(VectorIndexService::mapChunk)
                .list();
    }

    private static ChunkForIndex mapChunk(ResultSet rs, int rowNum) throws SQLException {
        return new ChunkForIndex(
                rs.getString("id"),
                rs.getString("doc_id"),
                rs.getInt("doc_version"),
                rs.getString("parent_chunk_id"),
                rs.getString("content"),
                rs.getInt("chunk_index"),
                (Integer) rs.getObject("page_number"),
                rs.getString("chunk_type"));
    }

    private static String vectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(Float.toString(embedding[i]));
        }
        return builder.append(']').toString();
    }

    private static String metadataJson(String kbId, ChunkForIndex chunk) {
        return """
                {"kbId":"%s","docId":"%s","docVersion":%d,"status":"published","chunkId":"%s","parentChunkId":"%s","chunkIndex":%d,"chunkType":"%s"}
                """.formatted(
                jsonEscape(kbId),
                jsonEscape(chunk.docId()),
                chunk.docVersion(),
                jsonEscape(chunk.id()),
                jsonEscape(chunk.parentChunkId()),
                chunk.chunkIndex(),
                jsonEscape(chunk.chunkType()))
                .trim();
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static int normalizeBatchSize(int batchSize) {
        return Math.max(1, batchSize);
    }
}
