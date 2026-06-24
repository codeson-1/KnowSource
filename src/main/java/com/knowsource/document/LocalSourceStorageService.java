package com.knowsource.document;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(prefix = "knowsource.storage", name = "type", havingValue = "local", matchIfMissing = true)
public class LocalSourceStorageService implements SourceStorageService {

    private final Path rootPath;

    public LocalSourceStorageService(@Value("${knowsource.storage.local.root:./data/sources}") String root) {
        this.rootPath = Path.of(root).toAbsolutePath().normalize();
    }

    @Override
    public StoredSource store(
            String kbId,
            String docId,
            int docVersion,
            String originalFilename,
            String contentType,
            InputStream inputStream) throws IOException {
        String safeFilename = sanitizeFilename(originalFilename);
        Path target = rootPath
                .resolve(kbId)
                .resolve(docId)
                .resolve("v" + docVersion)
                .resolve(safeFilename)
                .normalize();
        ensureWithinRoot(target);

        Files.createDirectories(target.getParent());
        long sizeBytes = Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        String sourceKey = "local://" + rootPath.relativize(target).toString().replace('\\', '/');
        return new StoredSource(sourceKey, safeFilename, contentType, sizeBytes);
    }

    @Override
    public InputStream open(String sourceKey) throws IOException {
        String relativePath = sourceKey.startsWith("local://") ? sourceKey.substring("local://".length()) : sourceKey;
        Path source = rootPath.resolve(relativePath).normalize();
        ensureWithinRoot(source);
        return Files.newInputStream(source);
    }

    @Override
    public void delete(String sourceKey) throws IOException {
        String relativePath = sourceKey.startsWith("local://") ? sourceKey.substring("local://".length()) : sourceKey;
        Path source = rootPath.resolve(relativePath).normalize();
        ensureWithinRoot(source);
        Files.deleteIfExists(source);
    }

    private void ensureWithinRoot(Path path) {
        if (!path.startsWith(rootPath)) {
            throw new IllegalArgumentException("Invalid source file path.");
        }
    }

    private String sanitizeFilename(String originalFilename) {
        String candidate = StringUtils.hasText(originalFilename) ? originalFilename.trim() : "source.txt";
        String name = Path.of(candidate).getFileName().toString();
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char ch = name.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '.' || ch == '-' || ch == '_') {
                safe.append(ch);
            } else {
                safe.append('_');
            }
        }

        String sanitized = safe.toString().toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(sanitized) || ".".equals(sanitized) || "..".equals(sanitized)) {
            return "source.txt";
        }
        return sanitized;
    }
}
