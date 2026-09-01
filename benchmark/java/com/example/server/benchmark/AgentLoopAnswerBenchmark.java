package com.example.server.benchmark;

import com.example.server.dto.AgentState;
import com.example.server.dto.AnalysisResult;
import com.example.server.dto.VideoChunk;
import com.example.server.dto.VideoContext;
import com.example.server.dto.VideoRetrievalIntent;
import com.example.server.service.AgentCheckpointService;
import com.example.server.service.AgentEvaluationService;
import com.example.server.service.AgentLoopService;
import com.example.server.service.AgentTelemetry;
import com.example.server.service.AnalysisStageService;
import com.example.server.service.AgentResultMerge;
import com.example.server.service.CitationAlignmentService;
import com.example.server.service.EvidenceVerificationService;
import com.example.server.service.LongVideoContextService;
import com.example.server.service.QdrantVectorStore;
import com.example.server.service.VideoChunkingService;
import com.example.server.service.VideoEvidenceRetrievalService;
import com.example.server.utils.DeepSeekUtils;
import com.example.server.utils.EmbeddingUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Offline harness for the frozen answer suite. It calls the production AgentLoop and retrieval
 * services while keeping checkpoints, stage events, Redis and Qdrant out of the benchmark.
 */
public final class AgentLoopAnswerBenchmark {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private AgentLoopAnswerBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Arguments arguments = Arguments.parse(args);
        String apiKey = requiredEnv("SILICONFLOW_API_KEY");
        String baseUrl = env("SILICONFLOW_BASE_URL", "https://api.siliconflow.cn/v1");
        String model = env("LLM_MODEL", "deepseek-ai/DeepSeek-V3.2");
        String embeddingModel = env("EMBEDDING_MODEL", "BAAI/bge-m3");
        long modelTimeoutSeconds = longEnv("LLM_TIMEOUT_SECONDS", 300);
        long maxDurationMs = longEnv("AGENT_MAX_DURATION_MS", 120_000);
        long maxEstimatedTokens = longEnv("AGENT_MAX_ESTIMATED_TOKENS", 50_000);

        AnswerSuite suite = MAPPER.readValue(arguments.suite().toFile(), AnswerSuite.class);
        List<AnswerQuestion> selectedQuestions = selectQuestions(suite.questions(), arguments);
        CorpusCatalog catalog = CorpusCatalog.load(arguments.corpusRoot(), selectedQuestions);

        ThreadPoolTaskExecutor modelExecutor = modelExecutor();
        try {
            BenchmarkRuntime runtime = BenchmarkRuntime.create(
                    apiKey,
                    baseUrl,
                    model,
                    embeddingModel,
                    modelTimeoutSeconds,
                    maxDurationMs,
                    maxEstimatedTokens,
                    modelExecutor,
                    catalog);
            execute(arguments, suite, selectedQuestions, catalog, runtime, model, embeddingModel,
                    maxDurationMs, maxEstimatedTokens);
        } finally {
            modelExecutor.shutdown();
        }
    }

    private static void execute(Arguments arguments,
                                AnswerSuite suite,
                                List<AnswerQuestion> questions,
                                CorpusCatalog catalog,
                                BenchmarkRuntime runtime,
                                String model,
                                String embeddingModel,
                                long maxDurationMs,
                                long maxEstimatedTokens) throws IOException {
        RunDocument document = loadOrCreateRun(arguments, suite, model, embeddingModel,
                maxDurationMs, maxEstimatedTokens, catalog);
        Map<String, RunItem> existing = new LinkedHashMap<>();
        document.items().forEach(item -> existing.put(item.id(), item));

        int position = 0;
        for (AnswerQuestion question : questions) {
            position++;
            RunItem previous = existing.get(question.id());
            if (arguments.resume() && previous != null && "completed".equals(previous.status())) {
                System.out.printf("[agent-loop] %d/%d %s skipped (completed)%n",
                        position, questions.size(), question.id());
                continue;
            }

            System.out.printf("[agent-loop] %d/%d %s started%n",
                    position, questions.size(), question.id());
            RunItem item = runQuestion(question, catalog, runtime);
            existing.put(question.id(), item);
            document = document.withItems(existing.values().stream().toList(),
                    completedStatus(questions, existing), Instant.now().toString());
            writeJson(arguments.output(), document);
            System.out.printf("[agent-loop] %d/%d %s %s elapsedMs=%d round=%s%n",
                    position, questions.size(), question.id(), item.status(), item.elapsedMs(),
                    item.finalState() == null ? "-" : item.finalState().round());
        }
    }

    private static RunItem runQuestion(AnswerQuestion question,
                                       CorpusCatalog catalog,
                                       BenchmarkRuntime runtime) {
        CorpusData corpus = catalog.bySampleId().get(question.sampleId());
        if (corpus == null) {
            return RunItem.failed(question, 0, "Missing corpus for " + question.sampleId());
        }

        long startedNanos = System.nanoTime();
        long mediaId = corpus.mediaId();
        VideoContext fullContext = new VideoContext(
                corpus.source(), question.q(), corpus.segments());
        String traceId = runtime.telemetry().start(mediaId, question.q());
        runtime.deepSeek().resetCaptures();
        try {
            AgentState state = runtime.agentLoop().run(mediaId, fullContext, null);
            List<RoundCapture> captures = runtime.deepSeek().captures().stream()
                    .map(capture -> new RoundCapture(
                            capture.round(),
                            capture.result(),
                            evidenceMetrics(runtime.evidenceVerification(), capture.context(), capture.result())))
                    .toList();
            Map<String, Object> finalMetrics = new LinkedHashMap<>(
                    runtime.evaluation().evaluate(fullContext, state));
            finalMetrics.putAll(evidenceMetrics(
                    runtime.evidenceVerification(), fullContext, state.result()));
            runtime.telemetry().flush(traceId);
            Map<String, Object> trace = runtime.telemetry().latest(mediaId, question.q());
            return new RunItem(
                    question.id(), question.sampleId(), question.split(), question.tag(),
                    question.answerability(), "completed", elapsedMs(startedNanos),
                    state, captures, finalMetrics, trace, null);
        } catch (RuntimeException error) {
            runtime.telemetry().flush(traceId);
            return RunItem.failed(question, elapsedMs(startedNanos), rootMessage(error));
        } finally {
            runtime.telemetry().clear();
        }
    }

    private static Map<String, Object> evidenceMetrics(EvidenceVerificationService verification,
                                                       VideoContext context,
                                                       AnalysisResult result) {
        Map<String, Object> metrics = new LinkedHashMap<>();
        List<String> conclusions = result == null ? List.of() : result.conclusions();
        List<AnalysisResult.Evidence> evidence = result == null ? List.of() : result.evidence();
        long supportedClaims = conclusions.stream()
                .filter(claim -> evidence.stream().anyMatch(item ->
                        verification.supportsClaim(context, claim, item)))
                .count();
        long supportedEvidence = evidence.stream()
                .filter(item -> verification.supported(context, item))
                .count();
        long coveredTimestamps = evidence.stream()
                .filter(item -> verification.timestampCovered(context, item))
                .count();
        metrics.put("conclusionCount", conclusions.size());
        metrics.put("supportedClaimCount", supportedClaims);
        metrics.put("unsupportedClaimCount", conclusions.size() - supportedClaims);
        metrics.put("claimEvidenceSupportRate", rate(supportedClaims, conclusions.size()));
        metrics.put("evidenceCount", evidence.size());
        metrics.put("supportedEvidenceCount", supportedEvidence);
        metrics.put("evidenceSupportRate", rate(supportedEvidence, evidence.size()));
        metrics.put("timestampCoveredCount", coveredTimestamps);
        metrics.put("timestampCoverageRate", rate(coveredTimestamps, evidence.size()));
        return metrics;
    }

    private static double rate(long numerator, long denominator) {
        return denominator == 0 ? 0 : (double) numerator / denominator;
    }

    private static RunDocument loadOrCreateRun(Arguments arguments,
                                               AnswerSuite suite,
                                               String model,
                                               String embeddingModel,
                                               long maxDurationMs,
                                               long maxEstimatedTokens,
                                               CorpusCatalog catalog) throws IOException {
        if (arguments.resume() && Files.exists(arguments.output())) {
            return MAPPER.readValue(arguments.output().toFile(), RunDocument.class);
        }
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("generatorModel", model);
        config.put("embeddingModel", embeddingModel);
        config.put("maxRounds", 2);
        config.put("maxDurationMs", maxDurationMs);
        config.put("maxEstimatedTokens", maxEstimatedTokens);
        config.put("initialRetrieval", "frozen query intent and embedding when cached; live otherwise; production in-memory ranking");
        config.put("criticRetrieval", "live query rewrite and embedding, production in-memory ranking");
        config.put("qdrantEnabled", false);
        config.put("suiteSha256", sha256(arguments.suite()));
        config.put("corpusSha256", catalog.corpusHashes());
        String now = Instant.now().toString();
        return new RunDocument(1, suite.suiteId(), "agent-loop-answer-" + now.replaceAll("[:.]", "-"),
                "running", now, now, config, List.of());
    }

    private static String completedStatus(List<AnswerQuestion> questions,
                                          Map<String, RunItem> existing) {
        boolean allPresent = questions.stream().allMatch(question -> existing.containsKey(question.id()));
        if (!allPresent) return "running";
        return questions.stream().map(question -> existing.get(question.id()))
                .anyMatch(item -> !"completed".equals(item.status()))
                ? "completed-with-errors"
                : "completed";
    }

    private static List<AnswerQuestion> selectQuestions(List<AnswerQuestion> questions,
                                                        Arguments arguments) {
        return questions.stream()
                .filter(question -> arguments.onlyId() == null
                        || question.id().equals(arguments.onlyId()))
                .limit(arguments.limit())
                .toList();
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.toAbsolutePath().getParent());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), value);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)));
        } catch (Exception error) {
            throw new IOException("Cannot hash " + path, error);
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        for (int depth = 0; depth < 12 && current.getCause() != null; depth++) {
            current = current.getCause();
        }
        return error.getClass().getSimpleName() + ": "
                + Optional.ofNullable(current.getMessage()).orElse(current.toString());
    }

    private static ThreadPoolTaskExecutor modelExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("answer-benchmark-model-");
        executor.setRejectedExecutionHandler((task, pool) -> {
            throw new TaskRejectedException("Answer benchmark model queue is full");
        });
        executor.initialize();
        return executor;
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String requiredEnv(String key) {
        String value = env(key, "");
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
        return value;
    }

    private static long longEnv(String key, long fallback) {
        String value = env(key, "");
        return value.isEmpty() ? fallback : Long.parseLong(value);
    }

    private record BenchmarkRuntime(
            RecordingDeepSeekUtils deepSeek,
            AgentLoopService agentLoop,
            AgentTelemetry telemetry,
            AgentEvaluationService evaluation,
            EvidenceVerificationService evidenceVerification
    ) {
        private static BenchmarkRuntime create(String apiKey,
                                               String baseUrl,
                                               String model,
                                               String embeddingModel,
                                               long timeoutSeconds,
                                               long maxDurationMs,
                                               long maxEstimatedTokens,
                                               ThreadPoolTaskExecutor modelExecutor,
                                               CorpusCatalog catalog) {
            StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
            AgentTelemetry telemetry = new AgentTelemetry(redis, MAPPER);
            RecordingDeepSeekUtils deepSeek = new RecordingDeepSeekUtils(
                    apiKey, baseUrl, model, timeoutSeconds, telemetry, MAPPER, modelExecutor,
                    catalog.intentByQuestion());
            CachedEmbeddingUtils embedding = new CachedEmbeddingUtils(
                    apiKey, baseUrl, embeddingModel, catalog.embeddingBySemanticQuery());
            AgentCheckpointService checkpoint = mock(AgentCheckpointService.class);
            when(checkpoint.loadChunks(anyLong())).thenAnswer(invocation ->
                    catalog.chunksByMediaId().get(invocation.getArgument(0, Long.class)));
            QdrantVectorStore qdrant = new QdrantVectorStore(false, "http://127.0.0.1:6333", "", "video_chunks");
            VideoEvidenceRetrievalService retrieval = new VideoEvidenceRetrievalService(
                    deepSeek, embedding, qdrant, telemetry);
            VideoChunkingService chunking = new VideoChunkingService(deepSeek, embedding, telemetry);
            LongVideoContextService longContext = new LongVideoContextService(
                    telemetry, checkpoint, chunking, retrieval);
            EvidenceVerificationService verification = new EvidenceVerificationService();
            // 引用对齐使用生产实例:离线评测必须观测到与线上一致的确定性对齐行为。
            CitationAlignmentService alignment = new CitationAlignmentService();
            AgentResultMerge merge = new AgentResultMerge();
            AnalysisStageService stages = mock(AnalysisStageService.class);
            AgentLoopService loop = new AgentLoopService(
                    deepSeek, longContext, checkpoint, telemetry, verification, alignment, merge, stages,
                    2, maxDurationMs, maxEstimatedTokens, 0);
            AgentEvaluationService evaluation = new AgentEvaluationService(checkpoint, verification);
            return new BenchmarkRuntime(deepSeek, loop, telemetry, evaluation, verification);
        }
    }

    private static final class RecordingDeepSeekUtils extends DeepSeekUtils {
        private final Map<String, VideoRetrievalIntent> intentByQuestion;
        private final List<ExecutionCapture> captures = new ArrayList<>();

        private RecordingDeepSeekUtils(String apiKey,
                                       String baseUrl,
                                       String model,
                                       long timeoutSeconds,
                                       AgentTelemetry telemetry,
                                       ObjectMapper objectMapper,
                                       ThreadPoolTaskExecutor modelExecutor,
                                       Map<String, VideoRetrievalIntent> intentByQuestion) {
            super(apiKey, baseUrl, model, timeoutSeconds, 0, 0, 0,
                    telemetry, objectMapper, modelExecutor);
            this.intentByQuestion = intentByQuestion;
        }

        @Override
        public VideoRetrievalIntent planRetrieval(String goal) {
            VideoRetrievalIntent cached = intentByQuestion.get(goal.trim());
            return cached == null ? super.planRetrieval(goal) : cached;
        }

        @Override
        public AnalysisResult execute(VideoContext context,
                                      AgentState.AgentPlan plan,
                                      AgentState.CriticResult previousCritique,
                                      AnalysisResult previousDraft,
                                      String modeInstruction) {
            AnalysisResult result = super.execute(context, plan, previousCritique, previousDraft, modeInstruction);
            captures.add(new ExecutionCapture(captures.size() + 1, context, result));
            return result;
        }

        private void resetCaptures() {
            captures.clear();
        }

        private List<ExecutionCapture> captures() {
            return List.copyOf(captures);
        }
    }

    private static final class CachedEmbeddingUtils extends EmbeddingUtils {
        private final Map<String, List<Double>> embeddingBySemanticQuery;

        private CachedEmbeddingUtils(String apiKey,
                                     String baseUrl,
                                     String model,
                                     Map<String, List<Double>> embeddingBySemanticQuery) {
            super(apiKey, baseUrl, model);
            this.embeddingBySemanticQuery = embeddingBySemanticQuery;
        }

        @Override
        public List<Double> embed(String text) {
            List<Double> cached = embeddingBySemanticQuery.get(text.trim());
            return cached == null ? super.embed(text) : cached;
        }
    }

    private record CorpusCatalog(
            Map<String, CorpusData> bySampleId,
            Map<Long, List<VideoChunk>> chunksByMediaId,
            Map<String, VideoRetrievalIntent> intentByQuestion,
            Map<String, List<Double>> embeddingBySemanticQuery,
            Map<String, String> corpusHashes
    ) {
        private static CorpusCatalog load(Path root, List<AnswerQuestion> questions) throws IOException {
            Map<String, CorpusData> bySample = new LinkedHashMap<>();
            Map<Long, List<VideoChunk>> chunksByMedia = new LinkedHashMap<>();
            Map<String, VideoRetrievalIntent> intentByQuestion = new ConcurrentHashMap<>();
            Map<String, List<Double>> embeddingByQuery = new ConcurrentHashMap<>();
            Map<String, String> hashes = new LinkedHashMap<>();
            List<String> sampleIds = questions.stream().map(AnswerQuestion::sampleId).distinct().toList();
            long mediaId = 10_000;
            for (String sampleId : sampleIds) {
                Path datasetPath = root.resolve(sampleId).resolve("dataset.json");
                JsonNode dataset = MAPPER.readTree(datasetPath.toFile());
                List<VideoChunk> chunks = MAPPER.convertValue(
                        dataset.path("chunks"), new TypeReference<List<VideoChunk>>() { });
                List<VideoContext.VideoSegment> segments = chunks.stream()
                        .flatMap(chunk -> chunk.rawSegments().stream())
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toMap(
                                segment -> segment.startMs() + ":" + segment.endMs(),
                                segment -> segment,
                                (left, right) -> left,
                                LinkedHashMap::new))
                        .values().stream()
                        .sorted(Comparator.comparingLong(VideoContext.VideoSegment::startMs))
                        .toList();
                String source = dataset.path("metadata").path("sourceUrl").asText(sampleId);
                CorpusData corpus = new CorpusData(mediaId, source, chunks, segments);
                bySample.put(sampleId, corpus);
                chunksByMedia.put(mediaId, chunks);
                hashes.put(sampleId, sha256(datasetPath));

                for (JsonNode cachedQuestion : dataset.path("questions")) {
                    if (!cachedQuestion.path("reviewed").asBoolean(false)) continue;
                    String question = cachedQuestion.path("q").asText();
                    VideoRetrievalIntent intent = MAPPER.treeToValue(
                            cachedQuestion.path("intent"), VideoRetrievalIntent.class);
                    List<Double> semanticEmbedding = MAPPER.convertValue(
                            cachedQuestion.path("semanticEmbedding"), new TypeReference<List<Double>>() { });
                    if (!question.isBlank() && intent != null && !intent.semanticQuery().isBlank()) {
                        intentByQuestion.put(question.trim(), intent);
                        if (!semanticEmbedding.isEmpty()) {
                            embeddingByQuery.put(intent.semanticQuery().trim(), semanticEmbedding);
                        }
                    }
                }
                mediaId++;
            }
            return new CorpusCatalog(bySample, chunksByMedia, intentByQuestion, embeddingByQuery, hashes);
        }
    }

    private record CorpusData(
            long mediaId,
            String source,
            List<VideoChunk> chunks,
            List<VideoContext.VideoSegment> segments
    ) {
    }

    private record ExecutionCapture(int round, VideoContext context, AnalysisResult result) {
    }

    public record RoundCapture(int round,
                               AnalysisResult result,
                               Map<String, Object> automaticMetrics) {
    }

    public record RunItem(String id,
                          String sampleId,
                          String split,
                          String tag,
                          String answerability,
                          String status,
                          long elapsedMs,
                          AgentState finalState,
                          List<RoundCapture> rounds,
                          Map<String, Object> automaticMetrics,
                          Map<String, Object> telemetry,
                          String error) {
        private static RunItem failed(AnswerQuestion question, long elapsedMs, String error) {
            return new RunItem(question.id(), question.sampleId(), question.split(), question.tag(),
                    question.answerability(), "failed", elapsedMs, null, List.of(), Map.of(), Map.of(), error);
        }
    }

    public record RunDocument(int schemaVersion,
                              String suiteId,
                              String runId,
                              String status,
                              String startedAt,
                              String completedAt,
                              Map<String, Object> config,
                              List<RunItem> items) {
        private RunDocument withItems(List<RunItem> nextItems, String nextStatus, String timestamp) {
            return new RunDocument(schemaVersion, suiteId, runId, nextStatus,
                    startedAt, timestamp, config, nextItems);
        }
    }

    public record AnswerSuite(String suiteId, List<AnswerQuestion> questions) {
        public AnswerSuite {
            questions = questions == null ? List.of() : List.copyOf(questions);
        }
    }

    public record AnswerQuestion(String id,
                                 String sampleId,
                                 String split,
                                 String q,
                                 String tag,
                                 String answerability) {
    }

    private record Arguments(Path suite,
                             Path corpusRoot,
                             Path output,
                             long limit,
                             String onlyId,
                             boolean resume) {
        private static Arguments parse(String[] args) {
            Map<String, String> values = new LinkedHashMap<>();
            for (String arg : args) {
                if (!arg.startsWith("--") || !arg.contains("=")) {
                    throw new IllegalArgumentException("Expected --name=value, received: " + arg);
                }
                int separator = arg.indexOf('=');
                values.put(arg.substring(2, separator), arg.substring(separator + 1));
            }
            Path suite = Path.of(values.getOrDefault("suite", "benchmark/data/answer_suite_v1.json"));
            Path corpus = Path.of(values.getOrDefault("corpus-root", "benchmark/local/corpora"));
            Path output = Path.of(values.getOrDefault(
                    "output", "benchmark/local/answer-eval/answer_suite_v1_raw.json"));
            long limit = Long.parseLong(values.getOrDefault("limit", String.valueOf(Long.MAX_VALUE)));
            String only = values.get("only");
            boolean resume = Boolean.parseBoolean(values.getOrDefault("resume", "true"));
            return new Arguments(suite, corpus, output, limit, only, resume);
        }
    }
}
