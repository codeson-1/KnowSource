package com.knowsource.document;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentTextExtractor {

    ExtractedDocument extract(String sourceKey, String fileType) throws IOException;
}
