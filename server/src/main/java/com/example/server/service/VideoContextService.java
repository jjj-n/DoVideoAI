package com.example.server.service;

import com.example.server.dto.VideoContext;
import com.example.server.dto.TranscriptSegment;
import com.example.server.utils.MinioUtils;
import com.example.server.utils.OcrUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class VideoContextService {

    private static final Logger log = LoggerFactory.getLogger(VideoContextService.class);
    private static final String EVIDENCE_OBJECT_PREFIX = "evidence-frames";
    private static final long SEGMENT_MS = 60_000L;

    private final SegmentedTranscriptionService transcriptionService;
    private final OcrUtils ocrUtils;
    private final MinioUtils minioUtils;
    private final KeyFrameExtractor keyFrameExtractor;
    private final ThreadPoolTaskExecutor asrExecutor;
    private final ThreadPoolTaskExecutor ocrExecutor;
    private final AgentTelemetry telemetry;

    public VideoContextService(SegmentedTranscriptionService transcriptionService,
                               OcrUtils ocrUtils,
                               MinioUtils minioUtils,
                               KeyFrameExtractor keyFrameExtractor,
                               @Qualifier("asrExecutor") ThreadPoolTaskExecutor asrExecutor,
                               @Qualifier("ocrExecutor") ThreadPoolTaskExecutor ocrExecutor,
                               AgentTelemetry telemetry) {
        this.transcriptionService = transcriptionService;
        this.ocrUtils = ocrUtils;
        this.minioUtils = minioUtils;
        this.keyFrameExtractor = keyFrameExtractor;
        this.asrExecutor = asrExecutor;
        this.ocrExecutor = ocrExecutor;
        this.telemetry = telemetry;
    }

    public VideoContext build(String videoPath, String userGoal) {
        return build(videoPath, userGoal, null);
    }

    public VideoContext build(String videoPath, String userGoal, String traceId) {
        String readableVideoPath = minioUtils.readableSource(videoPath);
        Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "video-context-" + UUID.randomUUID());
        List<String> uploadedEvidenceFrames = new CopyOnWriteArrayList<>();
        CountDownLatch branchesFinished = new CountDownLatch(2);
        boolean cleanupWorkDir = true;
        try {
            Files.createDirectories(workDir);
            // 两条分支各跑各的，单路挂掉还能带着另一半信息继续往下走。
            Future<BranchResult<TranscriptSegment>> transcriptFuture = submitBranch(
                    asrExecutor,
                    branchesFinished,
                    () -> transcriptionService.transcribe(
                            readableVideoPath, workDir.resolve("audio"), traceId));
            Future<BranchResult<FramePart>> frameFuture = submitBranch(
                    ocrExecutor,
                    branchesFinished,
                    () -> extractKeyFrames(
                            readableVideoPath, workDir.resolve("frames"), traceId, uploadedEvidenceFrames));
            try {
                long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(60);
                BranchResult<TranscriptSegment> transcriptResult = awaitBranch(transcriptFuture, deadline);
                BranchResult<FramePart> frameResult = awaitBranch(frameFuture, deadline);
                return finishContext(
                        videoPath, userGoal, traceId, transcriptResult, frameResult, uploadedEvidenceFrames);
            } catch (TimeoutException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支处理超过总时间预算", e);
            } catch (InterruptedException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("VideoContext 构建被中断", e);
            } catch (ExecutionException e) {
                cleanupWorkDir = cancelBranches(branchesFinished, transcriptFuture, frameFuture);
                throw new IllegalStateException("VideoContext 分支执行失败", e.getCause());
            }
        } catch (Exception e) {
            deleteEvidenceFrames(uploadedEvidenceFrames);
            throw new IllegalStateException("VideoContext 构建失败", e);
        } finally {
            if (cleanupWorkDir) {
                deleteDirectory(workDir);
            } else {
                log.warn("video_context_workdir_retained path={} reason=branch_still_running", workDir);
            }
        }
    }

    public void deleteEvidenceFrames(VideoContext context) {
        if (context == null) return;
        deleteEvidenceFrames(context.segments().stream()
                .flatMap(segment -> segment.evidenceFrames().stream())
                .distinct()
                .toList());
    }

    private void deleteEvidenceFrames(List<String> frames) {
        frames.stream()
                .filter(frame -> minioUtils.isManagedFile(frame, EVIDENCE_OBJECT_PREFIX))
                .distinct()
                .forEach(frame -> {
                    try {
                        minioUtils.removeFile(frame);
                    } catch (RuntimeException e) {
                        log.warn("evidence_frame_cleanup_failed frame={}", frame, e);
                    }
                });
    }

    private VideoContext finishContext(String videoPath,
                                       String userGoal,
                                       String traceId,
                                       BranchResult<TranscriptSegment> transcriptResult,
                                       BranchResult<FramePart> frameResult,
                                       List<String> uploadedEvidenceFrames) {
        if (transcriptResult.failed() && frameResult.failed()) {
            IllegalStateException failure = new IllegalStateException(
                    "ASR 和 OCR 分支均失败", transcriptResult.error());
            failure.addSuppressed(frameResult.error());
            throw failure;
        }
        if (transcriptResult.failed()) {
            telemetry.increment(traceId, "asrBranchFailures", 1);
            log.warn("video_context_asr_branch_failed", transcriptResult.error());
        }
        if (frameResult.failed()) {
            telemetry.increment(traceId, "ocrBranchFailures", 1);
            log.warn("video_context_ocr_branch_failed", frameResult.error());
            deleteEvidenceFrames(uploadedEvidenceFrames);
            uploadedEvidenceFrames.clear();
        }
        List<VideoContext.VideoSegment> segments = merge(transcriptResult.items(), frameResult.items());
        if (segments.isEmpty()) throw new IllegalStateException("视频未解析出有效语音或画面文字");
        return new VideoContext(videoPath, userGoal, segments);
    }

    private <T> Future<BranchResult<T>> submitBranch(
            ThreadPoolTaskExecutor executor,
            CountDownLatch branchesFinished,
            ThrowingSupplier<List<T>> work) {
        try {
            return executor.submit(() -> {
                try {
                    return BranchResult.success(work.get());
                } catch (Exception e) {
                    return BranchResult.failure(e);
                } finally {
                    branchesFinished.countDown();
                }
            });
        } catch (RuntimeException e) {
            branchesFinished.countDown();
            return CompletableFuture.completedFuture(BranchResult.failure(e));
        }
    }

    private <T> BranchResult<T> awaitBranch(Future<BranchResult<T>> future, long deadlineNanos)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) throw new TimeoutException("VideoContext 总时间预算已耗尽");
        return future.get(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private boolean cancelBranches(CountDownLatch branchesFinished, Future<?>... futures) {
        for (Future<?> future : futures) {
            if (future != null && !future.isDone()) future.cancel(true);
        }
        try {
            return branchesFinished.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private List<FramePart> extractKeyFrames(String videoPath,
                                             Path frameDir,
                                             String traceId,
                                             List<String> uploadedEvidenceFrames) throws Exception {
        List<KeyFrameExtractor.FrameInfo> frameInfos = keyFrameExtractor.extract(videoPath, frameDir);
        List<FramePart> result = new ArrayList<>();
        int failedFrames = 0;
        for (KeyFrameExtractor.FrameInfo info : frameInfos) {
            String ocrText;
            try {
                telemetry.increment(traceId, "ocrCalls", 1);
                ocrText = ocrUtils.recognize(info.framePath().toFile());
            } catch (RuntimeException e) {
                failedFrames++;
                telemetry.increment(traceId, "ocrFrameFailures", 1);
                log.warn("ocr_frame_failed frame={} timestampMs={}",
                        info.framePath().getFileName(), info.timestampMs(), e);
                continue;
            }
            String frameUrl;
            try {
                frameUrl = minioUtils.uploadLocalFile(
                        info.framePath().toFile(),
                        info.framePath().getFileName().toString(),
                        EVIDENCE_OBJECT_PREFIX);
                uploadedEvidenceFrames.add(frameUrl);
            } catch (Exception e) {
                telemetry.increment(traceId, "frameUploadFailures", 1);
                log.warn("evidence_frame_upload_failed frame={} timestampMs={}",
                        info.framePath().getFileName(), info.timestampMs(), e);
                frameUrl = videoPath + "#timestampMs=" + info.timestampMs();
            }
            result.add(new FramePart(info.timestampMs(), ocrText, frameUrl));
        }
        if (result.isEmpty() && failedFrames > 0) {
            throw new IllegalStateException("所有 OCR 关键帧均处理失败");
        }
        return result;
    }

    private List<VideoContext.VideoSegment> merge(List<TranscriptSegment> transcripts, List<FramePart> frames) {
        Map<Long, SegmentBuilder> windows = new TreeMap<>();
        for (TranscriptSegment transcript : transcripts) {
            long windowStart = windowStart(transcript.startMs());
            windows.computeIfAbsent(windowStart, SegmentBuilder::new).transcripts.add(transcript.text());
        }
        for (FramePart frame : frames) {
            long windowStart = windowStart(frame.timestampMs());
            SegmentBuilder segment = windows.computeIfAbsent(windowStart, SegmentBuilder::new);
            if (frame.ocrText() != null && !frame.ocrText().isBlank()) {
                segment.ocrTexts.add(frame.ocrText());
            }
            segment.evidenceFrames.add(frame.frameName());
        }
        return windows.values().stream().map(SegmentBuilder::build).toList();
    }

    private long windowStart(long timestampMs) {
        return timestampMs / SEGMENT_MS * SEGMENT_MS;
    }

    private void deleteDirectory(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception e) {
                    log.warn("temporary_file_cleanup_failed path={}", path, e);
                }
            });
        } catch (Exception e) {
            log.warn("temporary_directory_cleanup_failed path={}", directory, e);
        }
    }

    private record FramePart(long timestampMs, String ocrText, String frameName) {
    }

    private record BranchResult<T>(List<T> items, Exception error) {
        private static <T> BranchResult<T> success(List<T> items) {
            return new BranchResult<>(items, null);
        }

        private static <T> BranchResult<T> failure(Exception error) {
            return new BranchResult<>(List.of(), error);
        }

        private boolean failed() {
            return error != null;
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static class SegmentBuilder {
        private final long startMs;
        private final List<String> transcripts = new ArrayList<>();
        private final List<String> ocrTexts = new ArrayList<>();
        private final List<String> evidenceFrames = new ArrayList<>();

        private SegmentBuilder(long startMs) {
            this.startMs = startMs;
        }

        private VideoContext.VideoSegment build() {
            return new VideoContext.VideoSegment(
                    startMs,
                    startMs + SEGMENT_MS,
                    String.join("\n", transcripts),
                    ocrTexts,
                    evidenceFrames
            );
        }
    }
}
