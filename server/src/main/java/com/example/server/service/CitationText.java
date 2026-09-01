package com.example.server.service;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 引用文本归一化的单一事实源。{@link EvidenceVerificationService} 的严格校验与
 * {@link CitationAlignmentService} 的引用对齐必须共用同一套归一化规则，
 * 否则对齐产物无法构造性地通过校验。
 */
public final class CitationText {

    private static final Pattern STRIP_PATTERN = Pattern.compile("[\\p{P}\\p{S}\\s]");

    /** 每个 char 值的剥离判定只做一次正则匹配，保证与校验侧规则完全同源。 */
    private static final BitSet STRIPPED = new BitSet();
    private static final BitSet COMPUTED = new BitSet();

    private CitationText() {
    }

    /**
     * 与旧版 {@code EvidenceVerificationService#normalize} 逐字节一致的归一化：
     * lowercase(Locale.ROOT) 后去除全部 Unicode 标点、符号与空白。
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder(raw.length());
        int index = 0;
        while (index < raw.length()) {
            int codePoint = raw.codePointAt(index);
            index += Character.charCount(codePoint);
            String lowered = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
            for (int i = 0; i < lowered.length(); i++) {
                char ch = lowered.charAt(i);
                if (!isStripped(ch)) out.append(ch);
            }
        }
        return out.toString();
    }

    /** 带归一化下标映射的视图，支持把归一化区间映射回原文连续子串。 */
    public static NormalizedView view(String raw) {
        return new NormalizedView(raw == null ? "" : raw);
    }

    /** 归一化文本的 bigram 多重集 Dice 系数；长度不足 2 时退化为全等判定。 */
    public static double bigramDice(String normalizedA, String normalizedB) {
        if (normalizedA == null || normalizedB == null) return 0;
        if (normalizedA.equals(normalizedB)) return 1;
        if (normalizedA.length() < 2 || normalizedB.length() < 2) return 0;
        Map<String, Integer> aCounts = new HashMap<>();
        for (int i = 0; i + 1 < normalizedA.length(); i++) {
            aCounts.merge(normalizedA.substring(i, i + 2), 1, Integer::sum);
        }
        int intersection = 0;
        for (int i = 0; i + 1 < normalizedB.length(); i++) {
            String bigram = normalizedB.substring(i, i + 2);
            Integer count = aCounts.get(bigram);
            if (count != null && count > 0) {
                aCounts.put(bigram, count - 1);
                intersection++;
            }
        }
        int totalBigrams = (normalizedA.length() - 1) + (normalizedB.length() - 1);
        return 2.0 * intersection / totalBigrams;
    }

    private static boolean isStripped(char ch) {
        if (!COMPUTED.get(ch)) {
            synchronized (CitationText.class) {
                if (!COMPUTED.get(ch)) {
                    if (STRIP_PATTERN.matcher(String.valueOf(ch)).matches()) {
                        STRIPPED.set(ch);
                    }
                    COMPUTED.set(ch);
                }
            }
        }
        return STRIPPED.get(ch);
    }

    public static final class NormalizedView {

        private final String raw;
        private final String normalized;
        private final int[] rawStarts;
        private final int[] rawEnds;

        private NormalizedView(String raw) {
            this.raw = raw;
            int[] starts = new int[Math.max(raw.length(), 1)];
            int[] ends = new int[Math.max(raw.length(), 1)];
            StringBuilder normalizedBuilder = new StringBuilder(raw.length());
            int kept = 0;
            int index = 0;
            while (index < raw.length()) {
                int codePoint = raw.codePointAt(index);
                int codePointEnd = index + Character.charCount(codePoint);
                String lowered = new String(Character.toChars(codePoint)).toLowerCase(Locale.ROOT);
                for (int i = 0; i < lowered.length(); i++) {
                    char ch = lowered.charAt(i);
                    if (!isStripped(ch)) {
                        starts[kept] = index;
                        ends[kept] = codePointEnd;
                        normalizedBuilder.append(ch);
                        kept++;
                    }
                }
                index = codePointEnd;
            }
            this.normalized = normalizedBuilder.toString();
            this.rawStarts = Arrays.copyOf(starts, kept);
            this.rawEnds = Arrays.copyOf(ends, kept);
        }

        public String normalized() {
            return normalized;
        }

        /**
         * 返回归一化区间 [normStart, normEndExclusive) 对应的原文连续子串。
         * 区间内部的标点/空白会被保留（它们在原文中确实存在），区间外侧的不会混入，
         * 因此对结果再次 normalize 恒等于 normalized().substring(normStart, normEndExclusive)。
         */
        public String rawSubstring(int normStart, int normEndExclusive) {
            if (normStart < 0 || normEndExclusive > rawStarts.length || normStart >= normEndExclusive) {
                throw new IndexOutOfBoundsException(
                        "normalized range [" + normStart + ", " + normEndExclusive
                                + ") outside kept length " + rawStarts.length);
            }
            return raw.substring(rawStarts[normStart], rawEnds[normEndExclusive - 1]);
        }
    }
}
