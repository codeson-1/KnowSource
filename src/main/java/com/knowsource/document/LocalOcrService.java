package com.knowsource.document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;

import javax.imageio.ImageIO;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class LocalOcrService {

    private final boolean enabled;
    private final String command;
    private final String language;
    private final Duration timeout;

    LocalOcrService(
            @Value("${knowsource.ingest.ocr.enabled:false}") boolean enabled,
            @Value("${knowsource.ingest.ocr.command:tesseract}") String command,
            @Value("${knowsource.ingest.ocr.language:chi_sim+eng}") String language,
            @Value("${knowsource.ingest.ocr.timeout-seconds:30}") long timeoutSeconds) {
        this.enabled = enabled;
        this.command = command;
        this.language = language;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
    }

    boolean enabled() {
        return enabled;
    }

    Optional<String> extractText(BufferedImage image) throws IOException, InterruptedException {
        if (!enabled || image == null) {
            return Optional.empty();
        }
        Path input = Files.createTempFile("knowsource-ocr-", ".png");
        Path outputBase = Files.createTempFile("knowsource-ocr-", "");
        Path outputText = Path.of(outputBase.toString() + ".txt");
        try {
            ImageIO.write(image, "png", input.toFile());
            Process process = new ProcessBuilder(
                    command,
                    input.toString(),
                    outputBase.toString(),
                    "-l",
                    language,
                    "--psm",
                    "6")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            if (process.exitValue() != 0 || !Files.exists(outputText)) {
                return Optional.empty();
            }
            String text = Files.readString(outputText, StandardCharsets.UTF_8).trim();
            return StringUtils.hasText(text) ? Optional.of(text) : Optional.empty();
        } finally {
            Files.deleteIfExists(input);
            Files.deleteIfExists(outputBase);
            Files.deleteIfExists(outputText);
        }
    }
}
