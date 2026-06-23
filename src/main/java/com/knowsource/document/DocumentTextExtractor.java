package com.knowsource.document;

import java.io.IOException;
import java.io.InputStream;

public interface DocumentTextExtractor {

    String extract(String sourceKey, String fileType) throws IOException;
}
