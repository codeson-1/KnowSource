package com.knowsource.document;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

class OssSourceStorageSmokeTest {

    @Test
    void storesReadsAndDeletesObjectInRealBucketWhenEnabled() throws Exception {
        assumeTrue("true".equalsIgnoreCase(env("KNOWSOURCE_OSS_SMOKE_ENABLED")),
                "Set KNOWSOURCE_OSS_SMOKE_ENABLED=true to run the real OSS smoke test.");

        OssSourceStorageService storage = new OssSourceStorageService(
                requiredEnv("KNOWSOURCE_STORAGE_OSS_ENDPOINT"),
                requiredEnv("KNOWSOURCE_STORAGE_OSS_BUCKET"),
                requiredEnv("KNOWSOURCE_STORAGE_OSS_ACCESS_KEY_ID"),
                requiredEnv("KNOWSOURCE_STORAGE_OSS_ACCESS_KEY_SECRET"),
                envOrDefault("KNOWSOURCE_STORAGE_OSS_KEY_PREFIX", "knowsource/smoke"),
                30);

        byte[] payload = ("KnowSource OSS smoke " + java.time.Instant.now())
                .getBytes(StandardCharsets.UTF_8);
        StoredSource storedSource = storage.store(
                "smoke-kb",
                "smoke-doc-" + java.util.UUID.randomUUID(),
                1,
                "source.txt",
                "text/plain",
                new ByteArrayInputStream(payload));

        try (var inputStream = storage.open(storedSource.sourceKey())) {
            byte[] roundTrip = inputStream.readAllBytes();
            org.assertj.core.api.Assertions.assertThat(roundTrip).isEqualTo(payload);
        } finally {
            storage.delete(storedSource.sourceKey());
        }
    }

    private static String requiredEnv(String name) {
        String value = env(name);
        assumeTrue(StringUtils.hasText(value), name + " is required for OSS smoke test.");
        return value.trim();
    }

    private static String envOrDefault(String name, String defaultValue) {
        String value = env(name);
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private static String env(String name) {
        return System.getenv(name);
    }
}
