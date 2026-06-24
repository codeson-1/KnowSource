package com.knowsource.document;

import java.io.IOException;
import java.io.InputStream;

public interface SourceStorageService {

    StoredSource store(
            String kbId,
            String docId,
            int docVersion,
            String originalFilename,
            String contentType,
            InputStream inputStream) throws IOException;

    InputStream open(String sourceKey) throws IOException;

    default void delete(String sourceKey) throws IOException {
    }
}
