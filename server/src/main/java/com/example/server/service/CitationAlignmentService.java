package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性引用对齐：Executor 输出后、Critic 校验前，把 evidence 的 content 修正为
 * 所引时间戳处 ASR/OCR 原文的精确子串，并把 claim 绑定回对应 conclusion 的原文。
 * 模型的换述、省略号、ASR 口误纠正、ASR+OCR 合并引用等"合理引用但非逐字"的偏差
 * 在这里被系统性消除；真正无法在原文中定位的引用保持原样，继续被
 * {@link EvidenceVerificationService} 判为 unsupported——质量门不放松。
 *
 * <p>搜索空间硬限定为覆盖 timestampMs 的段及其时间轴相邻段，绝不做全片搜索：
 * 换述内容若锚定到别处的相似文本，属于错引，宁可失败也不修。
 */
@Service
public class CitationAlignmentService {

    /** 归一化后短于该长度的 content 只允许精确匹配，防止短串误锚。 */
    private static final int MIN_QUERY_LEN = 8;
    private static final int BLOCK_LEN = 12;
    private static final int MAX_BLOCKS = 16;
    private static final int CORRIDOR_SLACK = 24;
    /** 引用含省略号时，模型明确声明删减了中间原文，源端跳过放宽到该长度。 */
    private static final int ELLIPSIS_SOURCE_SLACK = 400;
    private static final double MIN_CONTENT_COVERAGE = 0.75;
    private static final double MIN_CLAIM_DICE = 0.75;
    private static final double MIN_PART_CLAIM_DICE = 0.85;
    private static final double MIN_CLAIM_LENGTH_RATIO = 0.6;
    private static final double MAX_CLAIM_LENGTH_RATIO = 1.6;

    /**
     * 通道标签token："ASR: x"、"OCR: y"、"(OCR: ...)"、"OCRs:"、"Transcipt/Transcript:"
     * 以及括号注记式 "(ASR)"（大小写不敏感，模型也会写小写 transcript:/ocrTexts:）。
     * 模型把多通道内容拼成一条 evidence 时会插入这些标签,
     * 它们是字母/文字,不会被归一化剥除,必须显式拆分。
     */
    private static final Pattern LABEL_TOKEN =
            Pattern.compile("[(（]?\\s*((?i:ASR\\w*|OCR\\w*|Transcipt|Transcript))\\s*[:：]?\\s*[)）]?");
    private static final Pattern ELLIPSIS = Pattern.compile("[.。]{2,}|…");
    private static final Pattern CLAIM_SPLIT = Pattern.compile("[；;。]");

    public AnalysisResult align(VideoContext fullContext, AnalysisResult result) {
        if (fullContext == null || result == null || result.evidence().isEmpty()) return result;
        List<VideoContext.VideoSegment> timeline = sortedTimeline(fullContext);
        Map<String, CitationText.NormalizedView> viewCache = new HashMap<>();
        List<AnalysisResult.Evidence> alignedEvidence = new ArrayList<>(result.evidence().size());
        for (AnalysisResult.Evidence evidence : result.evidence()) {
            for (AnalysisResult.Evidence candidate : alignEvidence(timeline, viewCache, evidence)) {
                String boundClaim = bindClaim(candidate.claim(), result.conclusions());
                if (boundClaim != null && !boundClaim.equals(candidate.claim())) {
                    candidate = new AnalysisResult.Evidence(
                            candidate.timestampMs(), candidate.source(), candidate.content(), boundClaim);
                }
                alignedEvidence.add(candidate);
            }
        }
        if (alignedEvidence.size() == result.evidence().size()) {
            boolean anyDifference = false;
            for (int i = 0; i < alignedEvidence.size(); i++) {
                if (!alignedEvidence.get(i).equals(result.evidence().get(i))) {
                    anyDifference = true;
                    break;
                }
            }
            if (!anyDifference) return result;
        }
        return new AnalysisResult(result.title(), result.conclusions(),
                List.copyOf(alignedEvidence), result.suggestions(), result.sections());
    }

    /**
     * 把 claim 模糊绑定到最佳 conclusion 并返回其原文；无法可靠绑定时返回 null。
     * 绑定成功的 evidence.claim 与 conclusion 归一化后恒相等，
     * {@code supportsClaim} 只剩原文包含这一道门。
     */
    public String bindClaim(String claim, List<String> conclusions) {
        String normalizedClaim = CitationText.normalize(claim);
        if (normalizedClaim.isEmpty() || conclusions == null || conclusions.isEmpty()) return null;
        for (String conclusion : conclusions) {
            if (CitationText.normalize(conclusion).equals(normalizedClaim)) return conclusion;
        }
        String best = null;
        double bestDice = 0;
        for (String conclusion : conclusions) {
            String normalizedConclusion = CitationText.normalize(conclusion);
            if (!lengthRatioWithin(normalizedClaim, normalizedConclusion)) continue;
            double dice = CitationText.bigramDice(normalizedClaim, normalizedConclusion);
            if (dice > bestDice) {
                bestDice = dice;
                best = conclusion;
            }
        }
        if (bestDice >= MIN_CLAIM_DICE) return best;
        for (String part : CLAIM_SPLIT.split(claim)) {
            String normalizedPart = CitationText.normalize(part);
            if (normalizedPart.length() < MIN_QUERY_LEN) continue;
            String partBest = null;
            double partBestDice = 0;
            for (String conclusion : conclusions) {
                String normalizedConclusion = CitationText.normalize(conclusion);
                if (!lengthRatioWithin(normalizedPart, normalizedConclusion)) continue;
                double dice = CitationText.bigramDice(normalizedPart, normalizedConclusion);
                if (dice > partBestDice) {
                    partBestDice = dice;
                    partBest = conclusion;
                }
            }
            if (partBestDice >= MIN_PART_CLAIM_DICE) return partBest;
        }
        return null;
    }

    private List<AnalysisResult.Evidence> alignEvidence(List<VideoContext.VideoSegment> timeline,
                                                        Map<String, CitationText.NormalizedView> viewCache,
                                                        AnalysisResult.Evidence evidence) {
        List<LabeledPart> parts = splitLabeledParts(evidence.content());
        if (parts.size() >= 2) {
            List<AnalysisResult.Evidence> rows = new ArrayList<>(parts.size());
            boolean allAligned = true;
            for (LabeledPart part : parts) {
                if (CitationText.normalize(part.text()).length() < 2) continue;
                List<String> channels = part.channels() == null
                        ? channelOrder(evidence.source())
                        : partChannelOrder(part.channels(), evidence.source());
                Alignment alignment = alignContent(timeline, viewCache, evidence.timestampMs(),
                        channels, part.text());
                if (alignment == null) {
                    allAligned = false;
                    break;
                }
                rows.add(new AnalysisResult.Evidence(
                        alignment.relocated() ? alignment.segment().startMs() : evidence.timestampMs(),
                        alignment.sourceLabel(), alignment.content(), evidence.claim()));
            }
            if (allAligned && !rows.isEmpty()) return rows;
        }
        // 无标签,或只有一个前缀标签:去掉包装后按普通单条引用对齐
        String query = parts.size() == 1 ? parts.get(0).text() : evidence.content();
        List<String> channels = parts.size() == 1 && parts.get(0).channels() != null
                ? partChannelOrder(parts.get(0).channels(), evidence.source())
                : channelOrder(evidence.source());
        Alignment alignment = alignContent(timeline, viewCache, evidence.timestampMs(), channels, query);
        if (alignment == null) return List.of(evidence);
        long timestamp = alignment.relocated() ? alignment.segment().startMs() : evidence.timestampMs();
        return List.of(new AnalysisResult.Evidence(
                timestamp, alignment.sourceLabel(), alignment.content(), evidence.claim()));
    }

    /**
     * 按通道标签把 content 切成带通道提示的段。带冒号的是前缀标签（其后是内容），
     * 括号无冒号的是后缀注记（标注其前的引文段）。没有标签时返回整条内容作为单段。
     */
    private List<LabeledPart> splitLabeledParts(String content) {
        List<LabeledPart> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        String currentChannel = null;
        Matcher matcher = LABEL_TOKEN.matcher(content);
        int cursor = 0;
        while (matcher.find()) {
            String label = matcher.group(1).toUpperCase(Locale.ROOT);
            boolean hasColon = matcher.group().contains(":") || matcher.group().contains("：");
            String channel = label.contains("OCR") ? "OCR" : "ASR";
            String segment = content.substring(cursor, matcher.start());
            current.append(segment);
            if (hasColon) {
                parts.add(new LabeledPart(stripWrappers(current.toString()), currentChannel));
                current = new StringBuilder();
                currentChannel = channel;
            } else {
                // 括号注记:结束当前段并回填通道
                parts.add(new LabeledPart(stripWrappers(current.toString()), channel));
                current = new StringBuilder();
                currentChannel = null;
            }
            cursor = matcher.end();
        }
        current.append(content.substring(cursor));
        parts.add(new LabeledPart(stripWrappers(current.toString()), currentChannel));
        return parts.stream().filter(part -> !part.text().isEmpty()).toList();
    }

    /** 去掉引文两侧的引号/括号包装与空白。 */
    private String stripWrappers(String value) {
        String trimmed = value.trim();
        while (!trimmed.isEmpty()) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            boolean firstWrapper = first == '"' || first == '\'' || first == '“' || first == '”'
                    || first == '「' || first == '『' || first == '(' || first == '（';
            boolean lastWrapper = last == '"' || last == '\'' || last == '“' || last == '”'
                    || last == '」' || last == '』' || last == ')' || last == '）';
            if (!firstWrapper && !lastWrapper) break;
            if (firstWrapper && (lastWrapper || trimmed.length() == 1)) {
                trimmed = trimmed.substring(Math.min(1, trimmed.length()),
                        Math.max(trimmed.length() - 1, 1)).trim();
            } else if (firstWrapper) {
                trimmed = trimmed.substring(1).trim();
            } else {
                trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }

    private List<String> partChannelOrder(String hintedChannel, String source) {
        List<String> order = new ArrayList<>(List.of(hintedChannel));
        for (String channel : channelOrder(source)) {
            if (!order.contains(channel)) order.add(channel);
        }
        return order;
    }

    private Alignment alignContent(List<VideoContext.VideoSegment> timeline,
                                   Map<String, CitationText.NormalizedView> viewCache,
                                   long timestampMs,
                                   List<String> channels,
                                   String content) {
        int sourceSlack = ELLIPSIS.matcher(content).find() ? ELLIPSIS_SOURCE_SLACK : CORRIDOR_SLACK;
        List<VideoContext.VideoSegment> covering = coveringSegments(timeline, timestampMs);
        Alignment match = bestAlignment(covering, viewCache, channels, content, sourceSlack);
        if (match != null) return match;
        // 相邻段重定位只纠错"模型引错了相邻段"，覆盖段内能定位的绝不迁移。
        return bestAlignment(adjacentSegments(timeline, timestampMs, covering),
                viewCache, channels, content, sourceSlack, true);
    }

    private Alignment bestAlignment(List<VideoContext.VideoSegment> segments,
                                    Map<String, CitationText.NormalizedView> viewCache,
                                    List<String> channels,
                                    String content,
                                    int sourceSlack) {
        return bestAlignment(segments, viewCache, channels, content, sourceSlack, false);
    }

    private Alignment bestAlignment(List<VideoContext.VideoSegment> segments,
                                    Map<String, CitationText.NormalizedView> viewCache,
                                    List<String> channels,
                                    String content,
                                    int sourceSlack,
                                    boolean relocated) {
        boolean preferSourceSkip = sourceSlack > CORRIDOR_SLACK;
        String query = CitationText.normalize(content);
        if (query.isEmpty() || segments.isEmpty()) return null;
        for (String channel : channels) {
            for (VideoContext.VideoSegment segment : segments) {
                CitationText.NormalizedView view = viewFor(segment, channel, viewCache);
                int index = view.normalized().indexOf(query);
                if (index >= 0) {
                    return new Alignment(segment, channel,
                            view.rawSubstring(index, index + query.length()), relocated, 1.0);
                }
            }
        }
        if (query.length() < MIN_QUERY_LEN) return null;
        List<QueryBlock> blocks = splitBlocks(query);
        Alignment best = null;
        for (String channel : channels) {
            for (VideoContext.VideoSegment segment : segments) {
                CitationText.NormalizedView view = viewFor(segment, channel, viewCache);
                for (int offset : voteOffsets(blocks, view)) {
                    Window window = corridorAlign(query, view, offset, sourceSlack, preferSourceSkip);
                    if (window.coverage() >= MIN_CONTENT_COVERAGE
                            && (best == null || window.coverage() > best.coverage())) {
                        best = new Alignment(segment, channel,
                                view.rawSubstring(window.start(), window.end()), relocated,
                                window.coverage());
                    }
                }
            }
        }
        return best;
    }

    /** k-gram 块偏移投票：块在原文中的每次出现都为"query 起点位于 p - 块query起点"投一票。 */
    private List<Integer> voteOffsets(List<QueryBlock> blocks, CitationText.NormalizedView view) {
        String haystack = view.normalized();
        Map<Integer, Integer> votes = new HashMap<>();
        for (QueryBlock block : blocks) {
            int from = 0;
            while (true) {
                int position = haystack.indexOf(block.text(), from);
                if (position < 0) break;
                votes.merge(position - block.start(), 1, Integer::sum);
                from = position + 1;
            }
        }
        // 负 offset 合法：模型在引用前加连接词时，正确窗口的预测起点为负，
        // 由 corridorAlign 钳制到 0 后再评估，不能在这里丢弃。
        return votes.entrySet().stream()
                .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(3)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * 走廊对齐：从候选 offset 出发双指针推进，源端/查询端各自容忍小跳过，返回覆盖率。
     * 覆盖率只计查询端未匹配字符——源端跳过是模型删减/省略号,不应扣分。
     * 省略号模式(preferSourceSkip)下源端跳过无条件优先:模型已声明删减,查询端巧合跳过不可信。
     */
    private Window corridorAlign(String query, CitationText.NormalizedView view, int offset,
                                 int sourceSlack, boolean preferSourceSkip) {
        String source = view.normalized();
        int n = query.length();
        int m = source.length();
        int start = Math.max(0, Math.min(offset, Math.max(m - 1, 0)));
        int qi = 0;
        int ci = start;
        int match = 0;
        while (qi < n && ci < m) {
            char queryChar = query.charAt(qi);
            char sourceChar = source.charAt(ci);
            if (queryChar == sourceChar) {
                qi++;
                ci++;
                match++;
                continue;
            }
            int sourceSkip = nextOccurrence(source, queryChar, ci, sourceSlack);
            int querySkip = nextOccurrence(query, sourceChar, qi, CORRIDOR_SLACK);
            if (sourceSkip >= 0 && (preferSourceSkip || querySkip < 0 || sourceSkip - ci <= querySkip - qi)) {
                ci = sourceSkip;
            } else if (querySkip >= 0) {
                qi = querySkip;
            } else {
                qi++;
                ci++;
            }
        }
        return new Window(start, Math.max(start + 1, Math.min(ci, m)), (double) match / n);
    }

    private int nextOccurrence(String text, char target, int fromExclusive, int slack) {
        int limit = Math.min(fromExclusive + slack, text.length() - 1);
        for (int i = fromExclusive + 1; i <= limit; i++) {
            if (text.charAt(i) == target) return i;
        }
        return -1;
    }

    private List<QueryBlock> splitBlocks(String query) {
        int n = query.length();
        int count = Math.max(1, Math.min(MAX_BLOCKS, (n + BLOCK_LEN - 1) / BLOCK_LEN));
        List<QueryBlock> blocks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int start = i * n / count;
            int end = (i + 1) * n / count;
            if (end > start) blocks.add(new QueryBlock(start, query.substring(start, end)));
        }
        return blocks;
    }

    private List<String> channelOrder(String source) {
        String upper = source == null ? "" : source.toUpperCase(Locale.ROOT);
        boolean asr = upper.contains("ASR");
        boolean ocr = upper.contains("OCR");
        // 拼接通道(ASR+OCR)永远排在纯通道之后:引用落在单个通道内时标签应保持精确
        if (asr && !ocr) return List.of("ASR", "ASR+OCR");
        if (ocr && !asr) return List.of("OCR", "ASR+OCR");
        if (asr) return List.of("ASR", "OCR", "ASR+OCR");
        return List.of("ASR", "OCR", "ASR+OCR");
    }

    private CitationText.NormalizedView viewFor(VideoContext.VideoSegment segment,
                                                String channel,
                                                Map<String, CitationText.NormalizedView> cache) {
        return cache.computeIfAbsent(segment.startMs() + "|" + channel,
                ignored -> CitationText.view(sourceText(segment, channel)));
    }

    /** 与 {@code EvidenceVerificationService.sourceText} 逐字节一致的通道原文。 */
    private String sourceText(VideoContext.VideoSegment segment, String channel) {
        return switch (channel) {
            case "ASR" -> segment.transcript();
            case "OCR" -> String.join(" ", segment.ocrTexts());
            default -> segment.transcript() + " " + String.join(" ", segment.ocrTexts());
        };
    }

    private List<VideoContext.VideoSegment> sortedTimeline(VideoContext context) {
        return context.segments().stream()
                .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                .toList();
    }

    private List<VideoContext.VideoSegment> coveringSegments(List<VideoContext.VideoSegment> timeline,
                                                             long timestampMs) {
        return timeline.stream()
                .filter(segment -> timestampMs >= segment.startMs() && timestampMs < segment.endMs())
                .toList();
    }

    /** 覆盖段为空时取时间轴上最接近的两段，否则取覆盖段的左右邻居。 */
    private List<VideoContext.VideoSegment> adjacentSegments(List<VideoContext.VideoSegment> timeline,
                                                             long timestampMs,
                                                             List<VideoContext.VideoSegment> covering) {
        if (timeline.isEmpty()) return List.of();
        int anchor;
        if (covering.isEmpty()) {
            anchor = 0;
            for (int i = 0; i < timeline.size(); i++) {
                if (timeline.get(i).startMs() <= timestampMs) anchor = i;
                else break;
            }
        } else {
            anchor = timeline.indexOf(covering.get(0));
        }
        List<VideoContext.VideoSegment> adjacent = new ArrayList<>(2);
        if (anchor - 1 >= 0) adjacent.add(timeline.get(anchor - 1));
        if (anchor + 1 < timeline.size()) adjacent.add(timeline.get(anchor + 1));
        return adjacent;
    }

    private boolean lengthRatioWithin(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        double ratio = (double) a.length() / b.length();
        return ratio >= MIN_CLAIM_LENGTH_RATIO && ratio <= MAX_CLAIM_LENGTH_RATIO;
    }

    private record QueryBlock(int start, String text) {
    }

    private record LabeledPart(String text, String channels) {
    }

    private record Window(int start, int end, double coverage) {
    }

    private record Alignment(VideoContext.VideoSegment segment,
                             String sourceLabel,
                             String content,
                             boolean relocated,
                             double coverage) {
    }
}
