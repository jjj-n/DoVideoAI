#!/usr/bin/env node

import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, '..', '..');
const serverRoot = path.join(repoRoot, 'server');
const suitePath = path.join(repoRoot, 'benchmark', 'data', 'answer_suite_v1.json');
const corpusRoot = path.join(repoRoot, 'benchmark', 'local', 'corpora');
const defaultRawPath = path.join(repoRoot, 'benchmark', 'local', 'answer-eval', 'answer_suite_v1_raw.json');
const defaultPublicPath = path.join(repoRoot, 'benchmark', 'data', 'answer_suite_v1_results.json');
const javaSource = path.join(
  repoRoot,
  'benchmark',
  'java',
  'com',
  'example',
  'server',
  'benchmark',
  'AgentLoopAnswerBenchmark.java',
);

const [mode, ...rawArgs] = process.argv.slice(2);
const args = parseArgs(rawArgs);

if (mode === 'run') {
  await runAgentLoop(args);
} else if (mode === 'score') {
  await scoreRun(args);
} else if (mode === 'validate') {
  await validatePublished(args);
} else {
  console.error('usage: node benchmark/scripts/answer_suite_eval.mjs run|score|validate [--name=value]');
  process.exit(1);
}

async function runAgentLoop(options) {
  const env = { ...await readRootEnv(), ...process.env };
  const javaHome = await resolveJavaHome(env);
  env.JAVA_HOME = javaHome;
  prependPath(env, path.join(javaHome, 'bin'));
  const java = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
  const javac = path.join(javaHome, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
  await requireJava21(java, env);

  const classpathFile = path.join(serverRoot, 'target', 'answer-benchmark-classpath.txt');
  const mvnw = path.join(serverRoot, process.platform === 'win32' ? 'mvnw.cmd' : 'mvnw');
  const mavenArgs = [
    '-q',
    '-DskipTests',
    'test-compile',
    'dependency:build-classpath',
    '-Dmdep.includeScope=test',
    `-Dmdep.outputFile=${classpathFile}`,
  ];
  if (process.platform === 'win32') {
    const cmd = path.join(env.SystemRoot || 'C:\\Windows', 'System32', 'cmd.exe');
    await runProcess(cmd, ['/d', '/c', 'call', mvnw, ...mavenArgs], { cwd: serverRoot, env });
  } else {
    await runProcess(mvnw, mavenArgs, { cwd: serverRoot, env });
  }

  const dependencies = (await fs.readFile(classpathFile, 'utf8')).trim();
  const classes = path.join(serverRoot, 'target', 'classes');
  const testClasses = path.join(serverRoot, 'target', 'test-classes');
  const buildRoot = path.join(repoRoot, 'benchmark', 'local', 'build', 'answer-suite');
  await fs.mkdir(buildRoot, { recursive: true });
  const compileClasspath = [classes, testClasses, dependencies].filter(Boolean).join(path.delimiter);
  await runProcess(javac, [
    '-encoding', 'UTF-8',
    '-cp', compileClasspath,
    '-d', buildRoot,
    javaSource,
  ], { cwd: repoRoot, env });

  const output = path.resolve(repoRoot, options.output || path.relative(repoRoot, defaultRawPath));
  const javaArgs = [
    '-cp', [buildRoot, compileClasspath].join(path.delimiter),
    'com.example.server.benchmark.AgentLoopAnswerBenchmark',
    `--suite=${suitePath}`,
    `--corpus-root=${corpusRoot}`,
    `--output=${output}`,
    `--resume=${options.resume ?? 'true'}`,
  ];
  if (options.limit) javaArgs.push(`--limit=${options.limit}`);
  if (options.only) javaArgs.push(`--only=${options.only}`);
  await runProcess(java, javaArgs, { cwd: repoRoot, env });
  console.log(JSON.stringify({ rawOutput: output }, null, 2));
}

async function scoreRun(options) {
  const env = { ...await readRootEnv(), ...process.env };
  const rawPath = path.resolve(repoRoot, options.input || path.relative(repoRoot, defaultRawPath));
  const outputPath = path.resolve(repoRoot, options.output || path.relative(repoRoot, defaultPublicPath));
  const [suite, raw] = await Promise.all([readJson(suitePath), readJson(rawPath)]);
  assert.equal(raw.suiteId, suite.suiteId, 'Raw run and answer suite IDs differ');
  const suiteById = new Map(suite.questions.map((question) => [question.id, question]));
  const judgeModel = env.ANSWER_EVAL_JUDGE_MODEL || env.LLM_MODEL || 'deepseek-ai/DeepSeek-V3.2';
  const cacheDir = path.join(repoRoot, 'benchmark', 'local', 'answer-eval', 'score-cache', raw.runId);
  await fs.mkdir(cacheDir, { recursive: true });

  const scored = [];
  let completed = 0;
  for (const item of raw.items) {
    const question = suiteById.get(item.id);
    assert(question, `Raw run contains unknown question ${item.id}`);
    if (item.status !== 'completed') {
      scored.push(publicFailedItem(question, item));
      continue;
    }
    const cachePath = path.join(cacheDir, `${item.id}.json`);
    let score;
    try {
      score = await readJson(cachePath);
    } catch (error) {
      if (error.code !== 'ENOENT') throw error;
      score = await requestJudge(env, judgeModel, question, item);
      await writeJson(cachePath, score);
    }
    scored.push(publicScoredItem(question, item, score));
    completed += 1;
    console.log(`[score] ${completed}/${raw.items.filter((entry) => entry.status === 'completed').length} ${item.id}`);
  }

  const result = buildPublishedResult(suite, raw, scored, judgeModel, await sha256File(rawPath));
  await writeJson(outputPath, result);
  validateResult(result, suite);
  console.log(JSON.stringify({ outputPath, summary: result.summary }, null, 2));
}

async function requestJudge(env, model, question, item) {
  const apiKey = env.SILICONFLOW_API_KEY;
  assert(apiKey, 'SILICONFLOW_API_KEY is required for scoring');
  const apiBase = (env.SILICONFLOW_BASE_URL || 'https://api.siliconflow.cn/v1').replace(/\/+$/, '');
  const answerable = question.answerability === 'answerable';
  const dimensions = answerable
    ? ['answerCorrectness', 'factualCompleteness', 'evidenceFidelity', 'citationCorrectness', 'citationSupport']
    : ['refusalAbility'];
  const payload = {
    model,
    temperature: 0.1,
    max_tokens: 1400,
    response_format: { type: 'json_object' },
    messages: [
      {
        role: 'system',
        content: [
          '你是严格的视频问答评测员，只依据给出的金标和视频证据规格评分。',
          '每个维度只能给 0、1、2 分：2=完全满足；1=部分满足或存在可核实缺口；0=错误、无依据或未完成。',
          '不得因为措辞相似就判满分；禁止断言、错误时间 Chunk、伪造引用或无证据猜测必须扣分。',
          '只返回 JSON，不使用 Markdown。每个 reason 用一句简短中文说明可审计依据。',
        ].join('\n'),
      },
      {
        role: 'user',
        content: JSON.stringify({
          outputSchema: {
            dimensions: Object.fromEntries(dimensions.map((name) => [name, { score: '0|1|2', reason: 'string' }])),
            overallNotes: 'string',
          },
          rubric: suiteRubricFor(question, dimensions),
          question: question.q,
          answerability: question.answerability,
          gold: question.evaluation,
          expectedEvidence: question.evidence,
          systemOutput: item.finalState.result,
          systemCritic: item.finalState.critique,
          automaticEvidenceChecks: item.automaticMetrics,
        }),
      },
    ],
  };

  let lastError;
  for (let attempt = 1; attempt <= 4; attempt += 1) {
    try {
      const response = await fetch(`${apiBase}/chat/completions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey}` },
        body: JSON.stringify(payload),
      });
      if (!response.ok) throw new Error(`Judge HTTP ${response.status}: ${(await response.text()).slice(0, 400)}`);
      const body = await response.json();
      const parsed = parseJsonObject(body.choices?.[0]?.message?.content);
      return normalizeJudge(parsed, dimensions, model);
    } catch (error) {
      lastError = error;
      if (attempt < 4) await sleep(1500 * (2 ** (attempt - 1)));
    }
  }
  throw new Error(`Judge failed for ${question.id}: ${lastError?.message || lastError}`);
}

function suiteRubricFor(question, dimensions) {
  const hardGates = new Set(['answerCorrectness', 'evidenceFidelity', 'citationCorrectness', 'citationSupport', 'refusalAbility']);
  return {
    maxScorePerDimension: 2,
    dimensions,
    hardGates: dimensions.filter((name) => hardGates.has(name)),
    hardGatePassRule: 'score must equal 2',
    citationRule: question.evaluation.citationRequirement,
  };
}

function normalizeJudge(value, dimensions, model) {
  assert(value && typeof value === 'object', 'Judge response is not an object');
  const source = value.dimensions || value.outputSchema?.dimensions || value;
  const normalized = {};
  for (const dimension of dimensions) {
    const entry = source[dimension];
    assert(entry && Number.isInteger(Number(entry.score)),
      `Judge omitted ${dimension}: ${JSON.stringify(value).slice(0, 800)}`);
    const score = Number(entry.score);
    assert(score >= 0 && score <= 2, `Judge returned invalid ${dimension} score`);
    normalized[dimension] = { score, reason: compactText(entry.reason, 240) };
  }
  return {
    judgeModel: model,
    dimensions: normalized,
    overallNotes: compactText(value.overallNotes || value.outputSchema?.overallNotes, 360),
  };
}

function publicScoredItem(question, item, judge) {
  const hardGateNames = question.answerability === 'answerable'
    ? ['answerCorrectness', 'evidenceFidelity', 'citationCorrectness', 'citationSupport']
    : ['refusalAbility'];
  const scores = Object.values(judge.dimensions).map((entry) => entry.score);
  const hardGatePassed = hardGateNames.every((name) => judge.dimensions[name]?.score === 2);
  return {
    id: item.id,
    sampleId: item.sampleId,
    split: item.split,
    tag: item.tag,
    answerability: item.answerability,
    status: item.status,
    elapsedMs: item.elapsedMs,
    round: item.finalState.round,
    criticPassed: Boolean(item.finalState.critique?.passed),
    automaticMetrics: item.automaticMetrics,
    firstRoundMetrics: item.rounds?.[0]?.automaticMetrics || null,
    telemetry: sanitizeTelemetry(item.telemetry),
    scoring: {
      score: scores.reduce((sum, value) => sum + value, 0),
      maxScore: scores.length * 2,
      hardGatePassed,
      dimensions: judge.dimensions,
      overallNotes: judge.overallNotes,
    },
    systemOutput: sanitizeOutput(item.finalState.result),
  };
}

function publicFailedItem(question, item) {
  return {
    id: item.id,
    sampleId: item.sampleId,
    split: item.split,
    tag: item.tag,
    answerability: item.answerability,
    status: item.status,
    elapsedMs: item.elapsedMs,
    error: compactText(item.error, 300),
  };
}

function sanitizeOutput(result) {
  return {
    title: result.title,
    conclusions: result.conclusions,
    evidence: result.evidence.map((item) => ({
      timestampMs: item.timestampMs,
      source: item.source,
      contentPreview: compactText(item.content, 100),
      contentSha256: sha256Text(item.content),
      claim: item.claim,
    })),
    suggestions: result.suggestions,
  };
}

function sanitizeTelemetry(telemetry) {
  return {
    stageDurationMs: telemetry?.stageDurationMs || {},
    counters: telemetry?.counters || {},
    values: telemetry?.values || {},
    estimatedCost: telemetry?.estimatedCost || 0,
  };
}

function buildPublishedResult(suite, raw, items, judgeModel, rawSha256) {
  const successful = items.filter((item) => item.status === 'completed');
  const failed = items.filter((item) => item.status !== 'completed');
  const summary = aggregateItems(successful, failed);
  return {
    schemaVersion: 1,
    suiteId: suite.suiteId,
    runId: raw.runId,
    status: failed.length ? 'completed-with-errors' : 'completed',
    generatedAt: new Date().toISOString(),
    methodology: {
      systemUnderTest: 'production AgentLoopService and VideoEvidenceRetrievalService',
      generatorModel: raw.config.generatorModel,
      judgeModel,
      executionBudget: {
        maxRounds: raw.config.maxRounds,
        maxDurationMs: raw.config.maxDurationMs,
        maxEstimatedTokens: raw.config.maxEstimatedTokens,
      },
      initialRetrieval: 'frozen query intent and embedding when an exact cache entry exists; live rewrite and embedding otherwise; production in-memory ranking',
      criticRetrieval: raw.config.criticRetrieval,
      qdrantEnabled: raw.config.qdrantEnabled,
      scoring: 'LLM-as-judge against frozen human-reviewed gold labels; 0/1/2 per rubric dimension',
      hardGatePassRule: 'all applicable hard-gate dimensions must score 2',
      rawArtifactSha256: rawSha256,
      suiteSha256: raw.config.suiteSha256,
      corpusSha256: raw.config.corpusSha256,
    },
    summary,
    limitations: [
      judgeModel === raw.config.generatorModel
        ? 'The generator and judge use the same model family; scores require human spot-checking before being presented as independent quality evidence.'
        : 'Scores are produced by an LLM judge and require human spot-checking before external claims.',
      'The initial query rewrite and embedding reuse an exact frozen cache entry when available and fall back to live generation otherwise; Critic-triggered retrieval is live.',
      'Qdrant is disabled in this offline run, so production in-memory cosine and keyword/OCR fallback ranking is evaluated.',
      'Automatic Claim-Evidence support verifies binding and ASR/OCR text containment, not semantic truth by itself.',
      'Full raw model outputs remain in benchmark/local and are identified here only by SHA-256.',
    ],
    groups: {
      bySplit: groupItems(items, (item) => item.split),
      byTag: groupItems(items, (item) => item.tag),
      byAnswerability: groupItems(items, (item) => item.answerability),
    },
    items,
  };
}

function aggregateItems(successful, failed) {
  const all = [...successful, ...failed];
  const scores = successful.map((item) => item.scoring.score);
  const maxScores = successful.map((item) => item.scoring.maxScore);
  const failedMaxScores = failed.map(maxScoreFor);
  const latencies = successful.map((item) => item.elapsedMs).sort((a, b) => a - b);
  const roundTwo = successful.filter((item) => item.round === 2);
  const refreshed = successful.filter((item) => Number(item.telemetry?.counters?.criticEvidenceRefreshes || 0) > 0);
  const firstCounts = sumEvidenceCounts(successful, 'firstRoundMetrics');
  const finalCounts = sumEvidenceCounts(successful, 'automaticMetrics');
  return {
    total: successful.length + failed.length,
    successful: successful.length,
    failed: failed.length,
    completionRate: ratio(successful.length, all.length),
    normalizedScore: ratio(sum(scores), sum(maxScores) + sum(failedMaxScores)),
    completedOnlyNormalizedScore: ratio(sum(scores), sum(maxScores)),
    hardGatePassRate: ratio(successful.filter((item) => item.scoring.hardGatePassed).length, all.length),
    completedOnlyHardGatePassRate: ratio(
      successful.filter((item) => item.scoring.hardGatePassed).length,
      successful.length,
    ),
    criticPassRate: ratio(successful.filter((item) => item.criticPassed).length, all.length),
    twoRoundTasks: roundTwo.length,
    secondRoundRecoveryRate: ratio(roundTwo.filter((item) => item.criticPassed).length, roundTwo.length),
    targetedEvidenceRefreshTasks: refreshed.length,
    targetedEvidenceRefreshRecoveryRate: ratio(refreshed.filter((item) => item.criticPassed).length, refreshed.length),
    firstRoundClaimEvidenceSupportRate: ratio(firstCounts.supported, firstCounts.total),
    finalClaimEvidenceSupportRate: ratio(finalCounts.supported, finalCounts.total),
    firstRoundUnsupportedClaimRate: ratio(firstCounts.total - firstCounts.supported, firstCounts.total),
    finalUnsupportedClaimRate: ratio(finalCounts.total - finalCounts.supported, finalCounts.total),
    latencyMs: {
      p50: percentile(latencies, 0.5),
      p95: percentile(latencies, 0.95),
      max: latencies.at(-1) || 0,
    },
  };
}

function groupItems(items, keyOf) {
  const groups = new Map();
  for (const item of items) {
    const key = keyOf(item);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(item);
  }
  return Object.fromEntries([...groups].map(([key, values]) => {
    const completed = values.filter((item) => item.status === 'completed');
    const score = sum(completed.map((item) => item.scoring.score));
    const maxScore = sum(values.map((item) => item.scoring?.maxScore ?? maxScoreFor(item)));
    return [key, {
      count: values.length,
      completed: completed.length,
      normalizedScore: ratio(score, maxScore),
      hardGatePassRate: ratio(completed.filter((item) => item.scoring.hardGatePassed).length, values.length),
    }];
  }));
}

function maxScoreFor(item) {
  return item.answerability === 'answerable' ? 10 : 2;
}

function sumEvidenceCounts(items, field) {
  return items.reduce((result, item) => {
    const metrics = item[field] || {};
    result.supported += Number(metrics.supportedClaimCount || 0);
    result.total += Number(metrics.conclusionCount || 0);
    return result;
  }, { supported: 0, total: 0 });
}

async function validatePublished(options) {
  const outputPath = path.resolve(repoRoot, options.input || path.relative(repoRoot, defaultPublicPath));
  const [suite, result] = await Promise.all([readJson(suitePath), readJson(outputPath)]);
  validateResult(result, suite);
  console.log(JSON.stringify({ valid: true, outputPath, summary: result.summary }, null, 2));
}

function validateResult(result, suite) {
  assert.equal(result.suiteId, suite.suiteId, 'Published result suite ID differs');
  assert.equal(result.summary.total, result.items.length, 'Summary total differs from item count');
  assert.equal(new Set(result.items.map((item) => item.id)).size, result.items.length, 'Duplicate result IDs');
  const expectedIds = new Set(suite.questions.map((question) => question.id));
  for (const item of result.items) assert(expectedIds.has(item.id), `Unknown result ID ${item.id}`);
  for (const key of ['completionRate', 'normalizedScore', 'hardGatePassRate', 'criticPassRate']) {
    const value = result.summary[key];
    assert(Number.isFinite(value) && value >= 0 && value <= 1, `Invalid summary ${key}`);
  }
}

async function resolveJavaHome(env) {
  if (env.JAVA_HOME) return env.JAVA_HOME;
  const localRuntime = path.join(repoRoot, 'benchmark', 'local', 'runtime', 'temurin-21');
  try {
    const entries = await fs.readdir(localRuntime, { withFileTypes: true });
    const candidate = entries.find((entry) => entry.isDirectory() && entry.name.startsWith('jdk-21'));
    if (candidate) return path.join(localRuntime, candidate.name);
  } catch (error) {
    if (error.code !== 'ENOENT') throw error;
  }
  throw new Error('JDK 21 is required. Set JAVA_HOME or place a portable JDK under benchmark/local/runtime/temurin-21/.');
}

async function requireJava21(java, env) {
  let output = '';
  await runProcess(java, ['-version'], {
    cwd: repoRoot,
    env,
    onOutput: (chunk) => { output += chunk; },
  });
  const match = output.match(/version\s+"(\d+)/);
  assert(match && Number(match[1]) >= 21, `JDK 21+ is required, received: ${output.trim()}`);
}

async function runProcess(command, commandArgs, options) {
  await new Promise((resolve, reject) => {
    const child = spawn(command, commandArgs, {
      cwd: options.cwd,
      env: options.env,
      shell: false,
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    const handle = (stream, fallback) => stream.on('data', (buffer) => {
      const text = buffer.toString();
      if (options.onOutput) options.onOutput(text);
      else fallback.write(text);
    });
    handle(child.stdout, process.stdout);
    handle(child.stderr, process.stderr);
    child.on('error', reject);
    child.on('exit', (code) => code === 0
      ? resolve()
      : reject(new Error(`${path.basename(command)} exited with code ${code}`)));
  });
}

async function readRootEnv() {
  try {
    return parseEnv(await fs.readFile(path.join(repoRoot, '.env'), 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return {};
    throw error;
  }
}

function parseEnv(text) {
  const values = {};
  for (const rawLine of text.split(/\r?\n/)) {
    const line = rawLine.trim();
    if (!line || line.startsWith('#')) continue;
    const separator = line.indexOf('=');
    if (separator < 1) continue;
    const key = line.slice(0, separator).trim();
    let value = line.slice(separator + 1).trim();
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1);
    }
    values[key] = value;
  }
  return values;
}

function parseArgs(values) {
  return Object.fromEntries(values.map((arg) => {
    assert(arg.startsWith('--') && arg.includes('='), `Expected --name=value, received ${arg}`);
    const separator = arg.indexOf('=');
    return [arg.slice(2, separator), arg.slice(separator + 1)];
  }));
}

function prependPath(env, value) {
  const keys = Object.keys(env).filter((key) => key.toLowerCase() === 'path');
  const current = keys.map((key) => env[key]).find(Boolean) || '';
  const targetKey = keys[0] || (process.platform === 'win32' ? 'Path' : 'PATH');
  for (const key of keys.slice(1)) delete env[key];
  env[targetKey] = `${value}${path.delimiter}${current}`;
}

function parseJsonObject(value) {
  const cleaned = String(value || '').trim().replace(/^```(?:json)?\s*/i, '').replace(/\s*```$/, '');
  return JSON.parse(cleaned);
}

function compactText(value, maxLength = 200) {
  const text = String(value || '').replace(/\s+/g, ' ').trim();
  return text.length <= maxLength ? text : `${text.slice(0, maxLength)}...`;
}

function percentile(sorted, quantile) {
  if (!sorted.length) return 0;
  return sorted[Math.min(sorted.length - 1, Math.ceil(sorted.length * quantile) - 1)];
}

function ratio(numerator, denominator) {
  return denominator ? Number((numerator / denominator).toFixed(6)) : 0;
}

function sum(values) {
  return values.reduce((total, value) => total + Number(value || 0), 0);
}

function sha256Text(value) {
  return crypto.createHash('sha256').update(String(value || ''), 'utf8').digest('hex');
}

async function sha256File(file) {
  return sha256Text(await fs.readFile(file));
}

async function readJson(file) {
  return JSON.parse(await fs.readFile(file, 'utf8'));
}

async function writeJson(file, value) {
  await fs.mkdir(path.dirname(file), { recursive: true });
  await fs.writeFile(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
