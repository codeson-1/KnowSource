package com.knowsource.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.util.StringUtils;

public final class TextTokenizer {

    private TextTokenizer() {
    }

    public static List<String> tokenize(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }

        List<String> tokens = new ArrayList<>();
        String normalized = text.toLowerCase(Locale.ROOT);
        StringBuilder latinRun = new StringBuilder();
        StringBuilder hanRun = new StringBuilder();

        normalized.codePoints().forEach(codePoint -> {
            if (isHan(codePoint)) {
                flushLatin(tokens, latinRun);
                hanRun.appendCodePoint(codePoint);
            } else if (Character.isLetterOrDigit(codePoint)) {
                flushHan(tokens, hanRun);
                latinRun.appendCodePoint(codePoint);
            } else {
                flushLatin(tokens, latinRun);
                flushHan(tokens, hanRun);
            }
        });
        flushLatin(tokens, latinRun);
        flushHan(tokens, hanRun);
        return tokens;
    }

    public static String joinForTsv(String text) {
        return String.join(" ", tokenize(text));
    }

    private static boolean isHan(int codePoint) {
        return Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN;
    }

    private static void flushLatin(List<String> tokens, StringBuilder latinRun) {
        if (latinRun.length() >= 2) {
            tokens.add(latinRun.toString());
        }
        latinRun.setLength(0);
    }

    private static void flushHan(List<String> tokens, StringBuilder hanRun) {
        if (hanRun.length() == 1) {
            tokens.add(hanRun.toString());
        } else {
            for (int i = 0; i < hanRun.length() - 1; i++) {
                tokens.add(hanRun.substring(i, i + 2));
            }
        }
        hanRun.setLength(0);
    }
}
