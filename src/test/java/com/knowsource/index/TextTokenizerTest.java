package com.knowsource.index;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TextTokenizerTest {

    @Test
    void tokenizesChineseAsHanBigrams() {
        assertThat(TextTokenizer.tokenize("混合检索方案"))
                .containsExactly("混合", "合检", "检索", "索方", "方案");
    }

    @Test
    void tokenizesLatinCodesAroundPunctuation() {
        assertThat(TextTokenizer.tokenize("qwen3-rerank ISO-27001 A1-B2"))
                .containsExactly("qwen3", "rerank", "iso", "27001", "a1", "b2");
    }

    @Test
    void joinsTokensForPostgresFullTextSearch() {
        assertThat(TextTokenizer.joinForTsv("VPN权限申请"))
                .isEqualTo("vpn 权限 限申 申请");
    }
}
