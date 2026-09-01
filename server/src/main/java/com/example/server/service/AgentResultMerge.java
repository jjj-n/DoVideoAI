package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 二轮定向修补的确定性合并:把一轮已核验通过的结论与证据保留下来,
 * 只让 LLM 在二轮修复 Critic 点名的缺口。
 *
 * <p>"二轮不劣化"由本组件构造性保证:一轮通过 EVS 核验的证据与结论,
 * 只要其目标仍在最终产物中,就会以原文形式出现在合并结果里。
 */
@Service
public class AgentResultMerge {

    /** 归一化后文本相等的阈值;超过此值视为"同一结论"。 */
    private static final double MIN_CONCLUSION_DICE = 0.8;
    /** 结论长度比超出此范围的不算近重复。 */
    private static final double MIN_CONCLUSION_LENGTH_RATIO = 0.6;
    private static final double MAX_CONCLUSION_LENGTH_RATIO = 1.6;

    public AnalysisResult merge(AnalysisResult previous, AnalysisResult draft,
                                VideoContext fullContext, EvidenceVerificationService evs) {
        if (previous == null || previous.evidence().isEmpty() || draft == null) return draft;

        // 一轮已核验的证据:通过 EVS 校验且绑定到某条结论
        List<VerifiedEvidence> verified = collectVerified(previous, fullContext, evs);
        if (verified.isEmpty()) return draft;

        // 1) 合并结论:近重复顶替 + 补回被丢弃的已验证结论
        List<String> mergedConclusions = new ArrayList<>(draft.conclusions());
        for (int i = 0; i < mergedConclusions.size(); i++) {
            String draftConclusion = mergedConclusions.get(i);
            VerifiedConclusion match = findMatchingConclusion(draftConclusion, verified);
            if (match != null) {
                mergedConclusions.set(i, match.originalText());
            }
        }
        Set<String> covered = new LinkedHashSet<>();
        for (String merged : mergedConclusions) {
            if (findMatchingConclusion(merged, verified) != null) {
                covered.add(findMatchingConclusion(merged, verified).originalText());
            }
        }
        for (VerifiedConclusion v : verifiedConclusions(verified)) {
            if (!covered.contains(v.originalText())) mergedConclusions.add(v.originalText());
        }

        // 2) 合并证据:已核验证据优先(以原文形式出现),与 draft 证据去重
        List<AnalysisResult.Evidence> mergedEvidence = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AnalysisResult.Evidence e : draft.evidence()) {
            AnalysisResult.Evidence replacement = replaceFromVerified(e, verified, mergedConclusions);
            String key = evidenceKey(replacement);
            if (seen.contains(key)) continue;
            seen.add(key);
            mergedEvidence.add(replacement);
        }
        for (VerifiedEvidence v : verified) {
            if (!mergedConclusions.stream().anyMatch(c ->
                    CitationText.normalize(c).equals(CitationText.normalize(v.boundConclusion())))) continue;
            AnalysisResult.Evidence candidate = v.originalEvidence();
            String key = evidenceKey(candidate);
            if (seen.contains(key)) continue;
            seen.add(key);
            mergedEvidence.add(candidate);
        }

        return new AnalysisResult(
                draft.title(), mergedConclusions, List.copyOf(mergedEvidence),
                draft.suggestions(), draft.sections());
    }

    private record VerifiedEvidence(
            AnalysisResult.Evidence originalEvidence, String boundConclusion) {
    }

    private record VerifiedConclusion(String originalText) {
    }

    private List<VerifiedEvidence> collectVerified(AnalysisResult previous,
                                                    VideoContext context,
                                                    EvidenceVerificationService evs) {
        List<VerifiedEvidence> verified = new ArrayList<>();
        for (AnalysisResult.Evidence evidence : previous.evidence()) {
            if (!evs.supported(context, evidence)) continue;
            String bound = previous.conclusions().stream()
                    .filter(c -> CitationText.normalize(c).equals(CitationText.normalize(evidence.claim())))
                    .findFirst().orElse(null);
            if (bound != null) verified.add(new VerifiedEvidence(evidence, bound));
        }
        return verified;
    }

    private List<VerifiedConclusion> verifiedConclusions(List<VerifiedEvidence> verified) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (VerifiedEvidence v : verified) seen.add(v.boundConclusion());
        return seen.stream().map(VerifiedConclusion::new).toList();
    }

    private VerifiedConclusion findMatchingConclusion(String draftConclusion,
                                                       List<VerifiedEvidence> verified) {
        String normalizedDraft = CitationText.normalize(draftConclusion);
        for (VerifiedEvidence v : verified) {
            if (CitationText.normalize(v.boundConclusion()).equals(normalizedDraft)) {
                return new VerifiedConclusion(v.boundConclusion());
            }
        }
        for (VerifiedEvidence v : verified) {
            String normalizedVerified = CitationText.normalize(v.boundConclusion());
            if (!lengthRatioWithin(normalizedDraft, normalizedVerified)) continue;
            double dice = CitationText.bigramDice(normalizedDraft, normalizedVerified);
            if (dice >= MIN_CONCLUSION_DICE) return new VerifiedConclusion(v.boundConclusion());
        }
        return null;
    }

    private AnalysisResult.Evidence replaceFromVerified(AnalysisResult.Evidence draftEvidence,
                                                         List<VerifiedEvidence> verified,
                                                         List<String> mergedConclusions) {
        for (VerifiedEvidence v : verified) {
            AnalysisResult.Evidence original = v.originalEvidence();
            if (original.timestampMs() != draftEvidence.timestampMs()) continue;
            if (!sourceMatches(original.source(), draftEvidence.source())) continue;
            // 顶替:使用原文,并把 claim 绑定到合并后的结论
            String newClaim = rebindClaim(draftEvidence.claim(), mergedConclusions, v.boundConclusion());
            return new AnalysisResult.Evidence(
                    original.timestampMs(), original.source(), original.content(), newClaim);
        }
        return draftEvidence;
    }

    private boolean sourceMatches(String previous, String current) {
        if (Objects.equals(previous, current)) return true;
        // ASR+OCR 拆分后拆成两条 ASR/OCR,任一都算匹配
        String normalizedPrevious = previous == null ? "" : previous.toUpperCase(java.util.Locale.ROOT);
        String normalizedCurrent = current == null ? "" : current.toUpperCase(java.util.Locale.ROOT);
        if (normalizedPrevious.contains("ASR+OCR")) {
            return normalizedCurrent.contains("ASR") || normalizedCurrent.contains("OCR");
        }
        return false;
    }

    private String rebindClaim(String draftClaim, List<String> mergedConclusions, String verifiedBound) {
        // 如果 draft claim 绑定的是 verified 那条结论,则替换为合并后的对应结论原文
        String normalizedDraft = CitationText.normalize(draftClaim);
        String normalizedBound = CitationText.normalize(verifiedBound);
        for (String merged : mergedConclusions) {
            if (CitationText.normalize(merged).equals(normalizedBound)) return merged;
            double dice = CitationText.bigramDice(normalizedDraft, CitationText.normalize(merged));
            if (dice >= MIN_CONCLUSION_DICE) {
                double diceToVerified = CitationText.bigramDice(normalizedBound, CitationText.normalize(merged));
                if (diceToVerified >= MIN_CONCLUSION_DICE) return merged;
            }
        }
        return draftClaim;
    }

    private String evidenceKey(AnalysisResult.Evidence evidence) {
        return evidence.timestampMs() + "|" + evidence.source() + "|"
                + CitationText.normalize(evidence.content());
    }

    private boolean lengthRatioWithin(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        double ratio = (double) a.length() / b.length();
        return ratio >= MIN_CONCLUSION_LENGTH_RATIO && ratio <= MAX_CONCLUSION_LENGTH_RATIO;
    }
}
