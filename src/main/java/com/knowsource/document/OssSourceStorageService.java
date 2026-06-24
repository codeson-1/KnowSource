package com.knowsource.document;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@ConditionalOnProperty(prefix = "knowsource.storage", name = "type", havingValue = "oss")
public class OssSourceStorageService implements SourceStorageService {

    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final HttpClient httpClient;
    private final String endpoint;
    private final String bucket;
    private final String accessKeyId;
    private final String accessKeySecret;
    private final String keyPrefix;

    public OssSourceStorageService(
            @Value("${knowsource.storage.oss.endpoint:}") String endpoint,
            @Value("${knowsource.storage.oss.bucket:}") String bucket,
            @Value("${knowsource.storage.oss.access-key-id:}") String accessKeyId,
            @Value("${knowsource.storage.oss.access-key-secret:}") String accessKeySecret,
            @Value("${knowsource.storage.oss.key-prefix:knowsource/sources}") String keyPrefix,
            @Value("${knowsource.storage.oss.timeout-seconds:30}") long timeoutSeconds) {
        this.endpoint = requireConfig(endpoint, "knowsource.storage.oss.endpoint");
        this.bucket = requireConfig(bucket, "knowsource.storage.oss.bucket");
        this.accessKeyId = requireConfig(accessKeyId, "knowsource.storage.oss.access-key-id");
        this.accessKeySecret = requireConfig(accessKeySecret, "knowsource.storage.oss.access-key-secret");
        this.keyPrefix = trimSlashes(StringUtils.hasText(keyPrefix) ? keyPrefix.trim() : "knowsource/sources");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, timeoutSeconds)))
                .build();
    }

    @Override
    public StoredSource store(
            String kbId,
            String docId,
            int docVersion,
            String originalFilename,
            String contentType,
            InputStream inputStream) throws IOException {
        byte[] bytes = inputStream.readAllBytes();
        String safeFilename = sanitizeFilename(originalFilename);
        String objectKey = "%s/%s/%s/v%d/%s".formatted(keyPrefix, safeSegment(kbId), safeSegment(docId), docVersion, safeFilename);
        String normalizedContentType = StringUtils.hasText(contentType) ? contentType.trim() : "application/octet-stream";
        HttpRequest request = signedRequest("PUT", objectKey, normalizedContentType)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();
        send(request);
        return new StoredSource("oss://" + bucket + "/" + objectKey, safeFilename, normalizedContentType, bytes.length);
    }

    @Override
    public InputStream open(String sourceKey) throws IOException {
        String objectKey = objectKeyFromSourceKey(sourceKey);
        HttpRequest request = signedRequest("GET", objectKey, "")
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request);
        return new ByteArrayInputStream(response.body());
    }

    @Override
    public void delete(String sourceKey) throws IOException {
        String objectKey = objectKeyFromSourceKey(sourceKey);
        HttpRequest request = signedRequest("DELETE", objectKey, "")
                .DELETE()
                .build();
        send(request);
    }

    @Override
    public String previewUrl(String sourceKey, int ttlSeconds) {
        String objectKey = objectKeyFromSourceKey(sourceKey);
        long expires = Instant.now().plusSeconds(Math.max(60, ttlSeconds)).getEpochSecond();
        String canonicalResource = "/" + bucket + "/" + objectKey;
        String stringToSign = "GET\n\n\n" + expires + "\n" + canonicalResource;
        String signature = urlEncode(hmacSha1Base64(stringToSign, accessKeySecret));
        return objectUri(objectKey)
                + "?OSSAccessKeyId=" + urlEncode(accessKeyId)
                + "&Expires=" + expires
                + "&Signature=" + signature;
    }

    private HttpRequest.Builder signedRequest(String method, String objectKey, String contentType) {
        String date = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
        Map<String, String> ossHeaders = new TreeMap<>();
        String canonicalResource = "/" + bucket + "/" + objectKey;
        String stringToSign = method + "\n\n" + contentType + "\n" + date + "\n"
                + canonicalizedOssHeaders(ossHeaders)
                + canonicalResource;
        String authorization = "OSS " + accessKeyId + ":" + hmacSha1Base64(stringToSign, accessKeySecret);
        HttpRequest.Builder builder = HttpRequest.newBuilder(objectUri(objectKey))
                .timeout(Duration.ofSeconds(60))
                .header("Date", date)
                .header("Authorization", authorization);
        if (StringUtils.hasText(contentType)) {
            builder.header("Content-Type", contentType);
        }
        return builder;
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException {
        try {
            HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("OSS request failed with HTTP " + response.statusCode());
            }
            return response;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("OSS request was interrupted.", ex);
        }
    }

    private URI objectUri(String objectKey) {
        String normalizedEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return URI.create(normalizedEndpoint + "/" + objectKey);
    }

    private String objectKeyFromSourceKey(String sourceKey) {
        String prefix = "oss://" + bucket + "/";
        if (!sourceKey.startsWith(prefix)) {
            throw new IllegalArgumentException("Invalid OSS source key.");
        }
        String objectKey = sourceKey.substring(prefix.length());
        if (!StringUtils.hasText(objectKey) || objectKey.contains("..")) {
            throw new IllegalArgumentException("Invalid OSS source key.");
        }
        return objectKey;
    }

    private String canonicalizedOssHeaders(Map<String, String> ossHeaders) {
        StringBuilder builder = new StringBuilder();
        ossHeaders.forEach((name, value) -> builder.append(name.toLowerCase(Locale.ROOT))
                .append(':')
                .append(value.trim())
                .append('\n'));
        return builder.toString();
    }

    private String hmacSha1Base64(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to sign OSS request.", ex);
        }
    }

    private String sanitizeFilename(String originalFilename) {
        String candidate = StringUtils.hasText(originalFilename) ? originalFilename.trim() : "source.txt";
        String name = java.nio.file.Path.of(candidate).getFileName().toString();
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

    private String safeSegment(String value) {
        if (!StringUtils.hasText(value) || value.contains("/") || value.contains("\\") || value.contains("..")) {
            throw new IllegalArgumentException("Invalid storage key segment.");
        }
        return value;
    }

    private String trimSlashes(String value) {
        String trimmed = value;
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String requireConfig(String value, String propertyName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(propertyName + " is required when OSS storage is enabled.");
        }
        return value.trim();
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
