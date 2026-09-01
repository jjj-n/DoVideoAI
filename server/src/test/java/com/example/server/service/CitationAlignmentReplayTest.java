package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 离线回放标定：用 benchmark 已保存的真实模型输出重放 {@link CitationAlignmentService#align},
 * 在不调用任何模型的情况下估计引用对齐对 Claim-Evidence 支持率的先验增量,
 * 并把 coverage/Dice 阈值标定到"可挽救的换述基本覆盖、真换述基本不放行"。
 *
 * <p>需要本地评测产物（不入 Git）：benchmark/local/answer-eval/answer_suite_v1_raw.json
 * 与 benchmark/local/corpora 下的各语料 dataset.json。通过环境变量 REPLAY_RAW=1 启用，
 * 运行方式：REPLAY_RAW=1 mvn test -Dtest=CitationAlignmentReplayTest -Dsurefire.useFile=false
 */
@EnabledIfEnvironmentVariable(named = "REPLAY_RAW", matches = ".+")
class CitationAlignmentReplayTest {

    private final CitationAlignmentService alignment = new CitationAlignmentService();
    private final EvidenceVerificationService verification = new EvidenceVerificationService();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void replaysSavedExecutorOutputThroughAlignment() throws Exception {
        Path rawPath = locate("benchmark/local/answer-eval/answer_suite_v1_raw.json");
        JsonNode root = objectMapper.readTree(Files.readAllBytes(rawPath));
        Map<String, VideoContext> corpora = new HashMap<>();

        int items = 0;
        long beforeConclusions = 0;
        long beforeSupportedClaims = 0;
        long beforeEvidence = 0;
        long beforeSupportedEvidence = 0;
        long afterConclusions = 0;
        long afterSupportedClaims = 0;
        long afterEvidence = 0;
        long afterSupportedEvidence = 0;
        int itemsImproved = 0;
        int itemsDegraded = 0;
        List<String> residualEvidenceSamples = new ArrayList<>();
        List<String> residualClaimSamples = new ArrayList<>();
        int residualContentFailures = 0;
        int residualBindingFailures = 0;

        for (JsonNode item : root.path("items")) {
            if (!"completed".equals(item.path("status").asText())) continue;
            VideoContext context = corpora.computeIfAbsent(
                    item.path("sampleId").asText(), this::loadCorpus);
            for (JsonNode round : item.path("rounds")) {
                JsonNode resultNode = round.path("result");
                if (resultNode.isMissingNode() || resultNode.isNull()) continue;
                AnalysisResult result = objectMapper.treeToValue(resultNode, AnalysisResult.class);
                if (result.evidence().isEmpty()) continue;
                items++;

                Metrics before = metrics(context, result);
                AnalysisResult aligned = alignment.align(context, result);
                Metrics after = metrics(context, aligned);

                for (AnalysisResult.Evidence evidence : aligned.evidence()) {
                    if (verification.supported(context, evidence)) continue;
                    residualContentFailures++;
                    if (residualEvidenceSamples.size() < 12) {
                        residualEvidenceSamples.add(String.format(
                                "[%s r%d %dms %s] %s", item.path("id").asText(),
                                round.path("round").asInt(), evidence.timestampMs(), evidence.source(),
                                truncate(evidence.content(), 80)));
                    }
                }
                for (String conclusion : aligned.conclusions()) {
                    boolean supported = aligned.evidence().stream()
                            .anyMatch(evidence -> verification.supportsClaim(context, conclusion, evidence));
                    if (supported) continue;
                    boolean hasContentSupportedEvidence = aligned.evidence().stream()
                            .anyMatch(evidence -> verification.supported(context, evidence));
                    if (hasContentSupportedEvidence) residualBindingFailures++;
                    if (residualClaimSamples.size() < 12) {
                        residualClaimSamples.add(String.format(
                                "[%s r%d] %s", item.path("id").asText(),
                                round.path("round").asInt(), truncate(conclusion, 80)));
                    }
                }

                beforeConclusions += before.conclusions();
                beforeSupportedClaims += before.supportedClaims();
                beforeEvidence += before.evidence();
                beforeSupportedEvidence += before.supportedEvidence();
                afterConclusions += after.conclusions();
                afterSupportedClaims += after.supportedClaims();
                afterEvidence += after.evidence();
                afterSupportedEvidence += after.supportedEvidence();

                double beforeRate = rate(before.supportedClaims(), before.conclusions());
                double afterRate = rate(after.supportedClaims(), after.conclusions());
                if (afterRate > beforeRate + 1e-9) itemsImproved++;
                if (afterRate < beforeRate - 1e-9) itemsDegraded++;
            }
        }

        System.out.printf("""
                        replay=%s items=%d
                        claim support   : %d/%d (%.2f%%) -> %d/%d (%.2f%%)
                        evidence support: %d/%d (%.2f%%) -> %d/%d (%.2f%%)
                        per-round improved=%d degraded=%d
                        residual content failures=%d binding failures=%d
                        """,
                rawPath,
                items,
                beforeSupportedClaims, beforeConclusions, percent(beforeSupportedClaims, beforeConclusions),
                afterSupportedClaims, afterConclusions, percent(afterSupportedClaims, afterConclusions),
                beforeSupportedEvidence, beforeEvidence, percent(beforeSupportedEvidence, beforeEvidence),
                afterSupportedEvidence, afterEvidence, percent(afterSupportedEvidence, afterEvidence),
                itemsImproved, itemsDegraded,
                residualContentFailures, residualBindingFailures);
        System.out.println("--- residual unsupported evidence ---");
        residualEvidenceSamples.forEach(sample -> System.out.println("  " + sample));
        System.out.println("--- residual unsupported claims ---");
        residualClaimSamples.forEach(sample -> System.out.println("  " + sample));

        assertTrue(items >= 40, "expected the full suite in replay, got " + items);
        assertTrue(afterSupportedClaims >= beforeSupportedClaims,
                "alignment must not reduce aggregate supported claims");
        assertTrue(afterSupportedEvidence >= beforeSupportedEvidence,
                "alignment must not reduce aggregate supported evidence");
    }

    private record Metrics(int conclusions, int supportedClaims, int evidence, int supportedEvidence) {
    }

    private Metrics metrics(VideoContext context, AnalysisResult result) {
        int supportedClaims = 0;
        for (String conclusion : result.conclusions()) {
            boolean supported = result.evidence().stream()
                    .anyMatch(evidence -> verification.supportsClaim(context, conclusion, evidence));
            if (supported) supportedClaims++;
        }
        int supportedEvidence = 0;
        for (AnalysisResult.Evidence evidence : result.evidence()) {
            if (verification.supported(context, evidence)) supportedEvidence++;
        }
        return new Metrics(result.conclusions().size(), supportedClaims,
                result.evidence().size(), supportedEvidence);
    }

    private VideoContext loadCorpus(String sampleId) {
        try {
            Path dataset = locate("benchmark/local/corpora/" + sampleId + "/dataset.json");
            JsonNode root = objectMapper.readTree(Files.readAllBytes(dataset));
            Map<String, VideoContext.VideoSegment> segments = new HashMap<>();
            for (JsonNode chunk : root.path("chunks")) {
                for (JsonNode segmentNode : chunk.path("rawSegments")) {
                    VideoContext.VideoSegment segment = objectMapper.treeToValue(
                            segmentNode, VideoContext.VideoSegment.class);
                    segments.putIfAbsent(segment.startMs() + ":" + segment.endMs(), segment);
                }
            }
            List<VideoContext.VideoSegment> timeline = new ArrayList<>(segments.values());
            timeline.sort(Comparator.comparingLong(VideoContext.VideoSegment::startMs));
            return new VideoContext(sampleId, "", List.copyOf(timeline));
        } catch (Exception e) {
            throw new IllegalStateException("无法加载语料 " + sampleId, e);
        }
    }

    private Path locate(String relative) {
        for (Path base : List.of(Path.of(".."), Path.of("."))) {
            Path candidate = base.resolve(relative);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("找不到 " + relative + "，请在含 benchmark/ 的目录下运行");
    }

    private double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private double percent(long numerator, long denominator) {
        return rate(numerator, denominator) * 100;
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength) + "…";
    }
}
