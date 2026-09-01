package com.example.server.service;

import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationAlignmentServiceTest {

    private final CitationAlignmentService alignment = new CitationAlignmentService();
    private final EvidenceVerificationService verification = new EvidenceVerificationService();

    private final VideoContext context = new VideoContext("lesson.mp4", "总结课程", List.of(
            new VideoContext.VideoSegment(
                    120_000, 180_000,
                    "接下来我们讲解二叉树的前序遍历，先访问根节点，再递归左子树，最后递归右子树。",
                    List.of("前序遍历：根节点、左子树、右子树"),
                    List.of("frame_000125.jpg")),
            new VideoContext.VideoSegment(
                    180_000, 240_000,
                    "然后我们安装深度学习框架，然后一起看代码实现。",
                    List.of("框架安装：pip install demo-framework"),
                    List.of("frame_000210.jpg")),
            new VideoContext.VideoSegment(
                    240_000, 300_000,
                    "最后说明课程安排和期末考试时间。",
                    List.of("期末考试：第十六周"),
                    List.of("frame_000260.jpg"))));

    @Test
    void keepsResultUntouchedWhenEverythingIsAlreadyAligned() {
        AnalysisResult result = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(125_000, "OCR", "前序遍历：根节点、左子树、右子树", "课程讲解了前序遍历顺序")));

        assertSame(result, alignment.align(context, result));
    }

    @Test
    void replacesPunctuationAndCaseVariantsWithExactSourceText() {
        AnalysisResult result = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(125_000, "OCR", "前序遍历:根节点、左子树、右子树", "课程讲解了前序遍历顺序")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("前序遍历：根节点、左子树、右子树", aligned.evidence().get(0).content());
        assertTrue(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void trimsBoundaryRewritingConnectives() {
        AnalysisResult result = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(125_000, "OCR", "主要是前序遍历：根节点、左子树、右子树", "课程讲解了前序遍历顺序")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("前序遍历：根节点、左子树、右子树", aligned.evidence().get(0).content());
        assertTrue(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void toleratesSmallInternalEdits() {
        AnalysisResult result = result(List.of("安装了深度学习框架"), List.of(
                new AnalysisResult.Evidence(185_000, "ASR", "然后我们安转深度学习框架，然后一起看代码实现。", "安装了深度学习框架")));

        AnalysisResult aligned = alignment.align(context, result);

        // 句尾标点被归一化剥除,不在匹配窗口内——snap 产物以最后一个保留字符结尾。
        assertEquals("然后我们安装深度学习框架，然后一起看代码实现", aligned.evidence().get(0).content());
        assertTrue(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void splitsMergedAsrOcrEvidenceIntoTwoRows() {
        AnalysisResult result = result(List.of("安装了深度学习框架"), List.of(
                new AnalysisResult.Evidence(185_000, "ASR+OCR",
                        "然后我们安装深度学习框架，然后一起看代码实现。 (OCR: 框架安装：pip install demo-framework)",
                        "安装了深度学习框架")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals(2, aligned.evidence().size());
        assertEquals("ASR", aligned.evidence().get(0).source());
        assertEquals("然后我们安装深度学习框架，然后一起看代码实现", aligned.evidence().get(0).content());
        assertEquals("OCR", aligned.evidence().get(1).source());
        assertEquals("框架安装：pip install demo-framework", aligned.evidence().get(1).content());
        assertTrue(verification.supported(context, aligned.evidence().get(0)));
        assertTrue(verification.supported(context, aligned.evidence().get(1)));
    }

    @Test
    void splitsColonLabeledVariantsIntoSeparateRows() {
        AnalysisResult result = result(List.of("安装了深度学习框架"), List.of(
                new AnalysisResult.Evidence(185_000, "ASR+OCR",
                        "ASR: 然后我们安装深度学习框架，然后一起看代码实现。OCRs: 框架安装：pip install demo-framework",
                        "安装了深度学习框架")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals(2, aligned.evidence().size());
        assertEquals("ASR", aligned.evidence().get(0).source());
        assertEquals("然后我们安装深度学习框架，然后一起看代码实现", aligned.evidence().get(0).content());
        assertEquals("OCR", aligned.evidence().get(1).source());
        assertEquals("框架安装：pip install demo-framework", aligned.evidence().get(1).content());
        assertTrue(verification.supported(context, aligned.evidence().get(0)));
        assertTrue(verification.supported(context, aligned.evidence().get(1)));
    }

    @Test
    void reconstructsElidedMiddleOfEllipsisQuotes() {
        VideoContext longContext = new VideoContext("db.mp4", "总结", List.of(
                new VideoContext.VideoSegment(0, 60_000,
                        "本节首先回顾上节课的作业讲评与常见错误，接着讲解索引的底层结构，"
                                + "包括聚簇索引与二级索引的区别，然后演示如何使用执行计划分析慢查询，"
                                + "最后布置两道课后练习题。",
                        List.of(), List.of())));
        AnalysisResult quote = result(List.of("讲解了索引底层结构并布置了练习"), List.of(
                new AnalysisResult.Evidence(10_000, "ASR",
                        "本节首先回顾上节课的作业讲评与常见错误，接着讲解索引的底层结构...最后布置两道课后练习题。",
                        "讲解了索引底层结构并布置了练习")));

        AnalysisResult aligned = alignment.align(longContext, quote);

        // 省略号被展开:content 替换为含被删减中间段的原文连续子串
        assertEquals("本节首先回顾上节课的作业讲评与常见错误，接着讲解索引的底层结构，"
                        + "包括聚簇索引与二级索引的区别，然后演示如何使用执行计划分析慢查询，"
                        + "最后布置两道课后练习题",
                aligned.evidence().get(0).content());
        assertTrue(verification.supported(longContext, aligned.evidence().get(0)));
    }

    @Test
    void leavesTrueParaphraseAloneSoTheQualityGateStillRejectsIt() {
        AnalysisResult result = result(List.of("课程介绍了二叉树遍历方法"), List.of(
                new AnalysisResult.Evidence(125_000, "ASR", "老师先带大家复习了上节课的作业讲评", "课程介绍了二叉树遍历方法")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("老师先带大家复习了上节课的作业讲评", aligned.evidence().get(0).content());
        assertFalse(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void leavesFabricatedContentAlone() {
        AnalysisResult result = result(List.of("视频讲解了量子计算"), List.of(
                new AnalysisResult.Evidence(125_000, "ASR", "本视频系统讲解了量子计算的基本原理与前沿进展", "视频讲解了量子计算")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("本视频系统讲解了量子计算的基本原理与前沿进展", aligned.evidence().get(0).content());
        assertFalse(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void doesNotFuzzShortQueriesToAvoidMisAnchoring() {
        AnalysisResult result = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(125_000, "OCR", "讲了遍历树", "课程讲解了前序遍历顺序")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("讲了遍历树", aligned.evidence().get(0).content());
        assertFalse(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void relocatesToAdjacentSegmentOnlyWhenCoveringSegmentFails() {
        AnalysisResult wrongTimestamp = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(200_000, "ASR", "先访问根节点，再递归左子树", "课程讲解了前序遍历顺序")));

        AnalysisResult relocated = alignment.align(context, wrongTimestamp);

        assertEquals(120_000, relocated.evidence().get(0).timestampMs());
        assertTrue(verification.supported(context, relocated.evidence().get(0)));

        AnalysisResult rightTimestamp = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(125_000, "ASR", "先访问根节点，再递归左子树", "课程讲解了前序遍历顺序")));

        AnalysisResult stable = alignment.align(context, rightTimestamp);

        assertEquals(125_000, stable.evidence().get(0).timestampMs());
    }

    @Test
    void rewritesSourceWhenChannelLabelIsMissing() {
        AnalysisResult result = result(List.of("课程讲解了前序遍历顺序"), List.of(
                new AnalysisResult.Evidence(125_000, "字幕", "前序遍历：根节点、左子树、右子树", "课程讲解了前序遍历顺序")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("OCR", aligned.evidence().get(0).source());
        assertTrue(verification.supported(context, aligned.evidence().get(0)));
    }

    @Test
    void bindsParaphrasedClaimBackToConclusionText() {
        AnalysisResult result = result(
                List.of("课程讲解了二叉树的前序遍历顺序", "安装了深度学习框架"),
                List.of(new AnalysisResult.Evidence(125_000, "OCR",
                        "前序遍历：根节点、左子树、右子树", "课程讲解了二叉树的前序遍历次序")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("课程讲解了二叉树的前序遍历顺序", aligned.evidence().get(0).claim());
        assertTrue(verification.supportsClaim(context, "课程讲解了二叉树的前序遍历顺序", aligned.evidence().get(0)));
    }

    @Test
    void bindsConcatenatedClaimThroughPartMatching() {
        AnalysisResult result = result(
                List.of("课程讲解了二叉树的前序遍历顺序", "安装了深度学习框架"),
                List.of(new AnalysisResult.Evidence(125_000, "OCR",
                        "前序遍历：根节点、左子树、右子树", "课程讲解了二叉树的前序遍历顺序；安装了深度学习框架")));

        AnalysisResult aligned = alignment.align(context, result);

        assertEquals("课程讲解了二叉树的前序遍历顺序", aligned.evidence().get(0).claim());
    }

    @Test
    void leavesOrphanClaimUnbound() {
        AnalysisResult result = result(
                List.of("课程讲解了二叉树的前序遍历顺序"),
                List.of(new AnalysisResult.Evidence(125_000, "OCR",
                        "前序遍历：根节点、左子树、右子树", "考试安排在第十六周进行")));

        AnalysisResult aligned = alignment.align(context, result);

        // 孤儿 claim 不与任何 conclusion 绑定,因此任何 conclusion 都不被该 evidence 支持;
        // claim 与 evidence.claim 自身相等无意义,断言必须打在 conclusion 一侧。
        assertEquals("考试安排在第十六周进行", aligned.evidence().get(0).claim());
        for (String conclusion : aligned.conclusions()) {
            assertFalse(verification.supportsClaim(context, conclusion, aligned.evidence().get(0)));
        }
    }

    private AnalysisResult result(List<String> conclusions, List<AnalysisResult.Evidence> evidence) {
        return new AnalysisResult("测试产物", conclusions, evidence, List.of(), List.of());
    }
}
