package com.example.server.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Locale;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationTextTest {

    /** 旧版正则实现，作为 parity 的参照物，禁止改动。 */
    private String legacyNormalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
    }

    @Test
    void normalizeMatchesLegacyRegexOnFixedSamples() {
        List<String> samples = List.of(
                "前序遍历：根节点、左子树、右子树",
                "Hello, World! (OCR: 42%)",
                "混合中English与，全角标点！？",
                "emoji 𝕏 测试 𠀀 代理对",
                "首字母 İ 大写展开",
                "  \t\n\r ",
                "");
        for (String sample : samples) {
            assertEquals(legacyNormalize(sample), CitationText.normalize(sample), sample);
        }
    }

    @Test
    void normalizeMatchesLegacyRegexOnRandomText() {
        String alphabet = "abcXYZ019，。！？、；：()（）%＄@# \t字树遍历𝕏𠀀İ";
        Random random = new Random(20260901L);
        for (int iteration = 0; iteration < 500; iteration++) {
            int length = random.nextInt(60);
            StringBuilder builder = new StringBuilder(length);
            for (int i = 0; i < length; i++) {
                builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }
            String sample = builder.toString();
            assertEquals(legacyNormalize(sample), CitationText.normalize(sample), sample);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "前序遍历：根节点、左子树、右子树。",
            "Hello, World! (OCR: 42%)",
            "emoji 𝕏 测试 𠀀 代理对",
            "，。！！开始与结束都有标点，。"
    })
    void rawSubstringRenormalizesToNormalizedSubstring(String raw) {
        CitationText.NormalizedView view = CitationText.view(raw);
        String normalized = view.normalized();
        // 只在"保留字符组"边界上断言:一个码点的小写展开可能产出多个保留字符(含代理对),
        // 它们共享同一原文区间,劈开它们的区间会扩展回完整码点,这是设计行为。
        List<Integer> boundaries = groupBoundaries(raw, normalized);
        for (int start : boundaries) {
            for (int end : boundaries) {
                if (end <= start) continue;
                String rawSlice = view.rawSubstring(start, end);
                assertEquals(normalized.substring(start, end), CitationText.normalize(rawSlice),
                        "range [" + start + "," + end + ") of " + raw);
            }
        }
    }

    private List<Integer> groupBoundaries(String raw, String normalized) {
        List<Integer> boundaries = new java.util.ArrayList<>();
        int normalizedIndex = 0;
        boundaries.add(normalizedIndex);
        int index = 0;
        while (index < raw.length()) {
            int codePoint = raw.codePointAt(index);
            String lowered = new String(Character.toChars(codePoint))
                    .toLowerCase(java.util.Locale.ROOT);
            int kept = 0;
            for (int i = 0; i < lowered.length(); i++) {
                if (!legacyNormalize(String.valueOf(lowered.charAt(i))).isEmpty()) kept++;
            }
            normalizedIndex += kept;
            boundaries.add(normalizedIndex);
            index += Character.charCount(codePoint);
        }
        assertEquals(normalized.length(), normalizedIndex, "boundary walk must cover the whole normalization");
        return boundaries.stream().distinct().toList();
    }

    @Test
    void bigramDiceBehavesAsSimilarityMeasure() {
        assertEquals(1.0, CitationText.bigramDice("abcdefgh", "abcdefgh"));
        assertEquals(0.0, CitationText.bigramDice("abcdefgh", "zzzzzzzz"));
        double nearDuplicate = CitationText.bigramDice("abcdefghij", "abcdefxyij");
        assertTrue(nearDuplicate > 0.4 && nearDuplicate < 1.0, "expected partial similarity: " + nearDuplicate);
        assertEquals(0.0, CitationText.bigramDice("a", "a".repeat(10)));
    }

    @Test
    void viewReusesNormalizeForTheWholeString() {
        CitationText.NormalizedView view = CitationText.view("Hello, 世界！");
        assertSame(view.normalized(), view.normalized());
        assertEquals(CitationText.normalize("Hello, 世界！"), view.normalized());
    }
}
