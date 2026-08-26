#!/usr/bin/env node
/**
 * Rebuild a retrieval corpus and compare the historical and current formulas
 * on exactly the same chunks, labels, and embeddings.
 *
 * Extract media before questions are reviewed:
 *   node retrieval_formula_ab.mjs extract <video> <dataset.json>
 *
 * Draft 9 exact, 9 fuzzy, and 6 visual questions for human review:
 *   node retrieval_formula_ab.mjs draft <dataset.json> <questions.json>
 *
 * Enrich reviewed questions and attach them to an extracted dataset:
 *   node retrieval_formula_ab.mjs enrich <dataset.json> <questions.json> [output-dataset.json]
 *
 * One-shot preparation when reviewed questions already exist:
 *   node retrieval_formula_ab.mjs prepare <video> <questions.json> <dataset.json>
 *
 * Compare (offline):
 *   node retrieval_formula_ab.mjs compare <dataset.json> <results.json>
 */
import {
  access,
  mkdir,
  readFile,
  readdir,
  rm,
  writeFile,
} from 'node:fs/promises';
import { basename, dirname, join, resolve } from 'node:path';
import { tmpdir } from 'node:os';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';

const API_BASE = (process.env.SILICONFLOW_BASE_URL || 'https://api.siliconflow.cn/v1').replace(/\/+$/, '');
const EMBEDDING_MODEL = process.env.EMBEDDING_MODEL || 'BAAI/bge-m3';
const LLM_MODEL = process.env.LLM_MODEL || 'deepseek-ai/DeepSeek-V3.2';
const ASR_MODEL = process.env.ASR_MODEL || 'TeleAI/TeleSpeechASR';
const API_KEY = process.env.SILICONFLOW_API_KEY || '';
const FFMPEG = process.env.FFMPEG || 'ffmpeg';
const TESSERACT = process.env.TESSERACT || 'tesseract';
const SEGMENT_MS = 60_000;
const CHUNK_MS = 5 * 60_000;
const VIDEO_SECONDS = positiveInteger(process.env.VIDEO_SECONDS || 60 * 60, 'VIDEO_SECONDS');
const DATASET_ID = process.env.DATASET_ID || '';
const SOURCE_URL = process.env.SOURCE_URL || '';
const ASR_CONCURRENCY = Number(process.env.ASR_CONCURRENCY || 4);
const OCR_CONCURRENCY = Number(process.env.OCR_CONCURRENCY || 4);
const RETRIES = 3;
const DRAFT_COUNTS = Object.freeze({ exact: 9, fuzzy: 9, visual: 6 });

const [command, ...args] = process.argv.slice(2);

if (command === 'extract') {
  const [videoFile, datasetFile] = args;
  if (!videoFile || !datasetFile) usage();
  await extract(resolve(videoFile), resolve(datasetFile));
} else if (command === 'draft') {
  const [datasetFile, questionsFile] = args;
  if (!datasetFile || !questionsFile) usage();
  await draft(resolve(datasetFile), resolve(questionsFile));
} else if (command === 'enrich') {
  const [datasetFile, questionsFile, outputFile = datasetFile] = args;
  if (!datasetFile || !questionsFile) usage();
  await enrich(resolve(datasetFile), resolve(questionsFile), resolve(outputFile));
} else if (command === 'prepare') {
  const [videoFile, questionsFile, datasetFile] = args;
  if (!videoFile || !questionsFile || !datasetFile) usage();
  await prepare(resolve(videoFile), resolve(questionsFile), resolve(datasetFile));
} else if (command === 'compare') {
  const [datasetFile, resultsFile] = args;
  if (!datasetFile || !resultsFile) usage();
  await compare(resolve(datasetFile), resolve(resultsFile));
} else {
  usage();
}

function usage() {
  console.error('usage: retrieval_formula_ab.mjs extract <video> <dataset.json>');
  console.error('   or: retrieval_formula_ab.mjs draft <dataset.json> <questions.json>');
  console.error('   or: retrieval_formula_ab.mjs enrich <dataset.json> <questions.json> [output-dataset.json]');
  console.error('   or: retrieval_formula_ab.mjs prepare <video> <questions.json> <dataset.json>');
  console.error('   or: retrieval_formula_ab.mjs compare <dataset.json> <results.json>');
  process.exit(1);
}

async function prepare(videoFile, questionsFile, datasetFile) {
  requireApiKey('prepare');
  const questions = JSON.parse(await readFile(questionsFile, 'utf8'));
  validateQuestions(questions);
  const dataset = await buildCorpus(videoFile);
  const questionCacheFile = join(workDirectory(), 'questions_enriched.json');
  dataset.questions = await enrichQuestions(questions, questionCacheFile);
  dataset.metadata.questionsEnrichedAt = new Date().toISOString();
  await saveDataset(datasetFile, dataset);
}

async function extract(videoFile, datasetFile) {
  requireApiKey('extract');
  const dataset = await buildCorpus(videoFile);
  dataset.questions = [];
  await saveDataset(datasetFile, dataset);
}

async function draft(datasetFile, questionsFile) {
  requireApiKey('draft');
  const dataset = JSON.parse(await readFile(datasetFile, 'utf8'));
  if (!Array.isArray(dataset.chunks) || dataset.chunks.length === 0) {
    throw new Error('dataset must contain chunks before drafting questions');
  }
  const questions = await draftQuestions(dataset, join(workDirectory(), 'questions_draft_cache.json'));
  await mkdir(dirname(questionsFile), { recursive: true });
  await writeJson(questionsFile, questions);
  console.log(`draft questions written: ${questionsFile}`);
}

async function enrich(datasetFile, questionsFile, outputFile) {
  requireApiKey('enrich');
  const [dataset, questions] = await Promise.all([
    readFile(datasetFile, 'utf8').then(JSON.parse),
    readFile(questionsFile, 'utf8').then(JSON.parse),
  ]);
  if (!Array.isArray(dataset.chunks) || dataset.chunks.length === 0) {
    throw new Error('dataset must contain chunks before enriching questions');
  }
  validateQuestions(questions);
  dataset.questions = await enrichQuestions(
    questions,
    join(workDirectory(), 'questions_enriched.json'),
  );
  dataset.metadata.questionsEnrichedAt = new Date().toISOString();
  await saveDataset(outputFile, dataset);
}

function requireApiKey(operation) {
  if (!API_KEY) throw new Error(`SILICONFLOW_API_KEY is required for ${operation}`);
}

function workDirectory() {
  return resolve(
    process.env.DOV_RETRIEVAL_WORK || join(tmpdir(), 'dovideo-retrieval-ab', 'prepared'),
  );
}

async function buildCorpus(videoFile) {
  await access(videoFile);

  const workDir = workDirectory();
  await mkdir(workDir, { recursive: true });
  const clippedVideo = join(workDir, `video_${VIDEO_SECONDS}s.mp4`);
  await ensureClippedVideo(videoFile, clippedVideo);

  const segmentsFile = join(workDir, 'segments.json');
  let segments = await readJsonIfPresent(segmentsFile);
  if (!segments) {
    const [transcripts, frames] = await Promise.all([
      transcribeVideo(clippedVideo, workDir),
      extractAndOcrFrames(clippedVideo, workDir),
    ]);
    segments = mergeSegments(transcripts, frames);
    await writeJson(segmentsFile, segments);
  }
  console.log(`segments ready: ${segments.length}`);

  const chunkCacheFile = join(workDir, 'chunks.json');
  const chunks = await buildChunks(segments, chunkCacheFile);
  console.log(`chunks ready: ${chunks.length}`);

  return {
    metadata: {
      datasetId: DATASET_ID || basename(videoFile).replace(/\.[^.]+$/, ''),
      sourceVideo: basename(videoFile),
      sourceUrl: SOURCE_URL || null,
      durationSeconds: VIDEO_SECONDS,
      segmentMs: SEGMENT_MS,
      chunkMs: CHUNK_MS,
      asrModel: ASR_MODEL,
      embeddingModel: EMBEDDING_MODEL,
      llmModel: LLM_MODEL,
      preparedAt: new Date().toISOString(),
      scoringScope: 'chunk recall; segment reranking does not change whether the expected chunk is selected',
    },
    chunks,
    questions: [],
  };
}

async function saveDataset(datasetFile, dataset) {
  await mkdir(dirname(datasetFile), { recursive: true });
  await writeJson(datasetFile, dataset);
  console.log(`dataset written: ${datasetFile}`);
}

async function ensureClippedVideo(source, target) {
  if (await exists(target)) return;
  console.log(`clipping the first ${VIDEO_SECONDS} seconds...`);
  await run(FFMPEG, [
    '-y', '-i', source,
    '-t', String(VIDEO_SECONDS),
    '-map', '0:v:0', '-map', '0:a:0',
    '-c', 'copy', target,
  ]);
}

async function transcribeVideo(video, workDir) {
  const audioDir = join(workDir, 'audio');
  const cacheFile = join(workDir, 'asr.json');
  await mkdir(audioDir, { recursive: true });
  const existingAudio = (await readdir(audioDir)).filter((name) => name.endsWith('.mp3'));
  if (existingAudio.length === 0) {
    console.log('splitting audio into 60-second files...');
    await run(FFMPEG, [
      '-y', '-i', video,
      '-vn', '-acodec', 'libmp3lame',
      '-f', 'segment', '-segment_time', '60', '-reset_timestamps', '1',
      join(audioDir, 'audio_%03d.mp3'),
    ]);
  }
  const files = (await readdir(audioDir))
    .filter((name) => {
      const match = /^audio_(\d+)\.mp3$/.exec(name);
      return match && Number(match[1]) * 60 < VIDEO_SECONDS;
    })
    .sort()
    .map((name) => join(audioDir, name));
  const cache = (await readJsonIfPresent(cacheFile)) || {};
  for (let start = 0; start < files.length; start += ASR_CONCURRENCY) {
    const batch = files.slice(start, start + ASR_CONCURRENCY);
    await Promise.all(batch.map(async (file) => {
      const key = basename(file);
      if (cache[key]) return;
      cache[key] = await transcribe(file);
      console.log(`ASR ${key}: ${cache[key].length} chars`);
    }));
    await writeJson(cacheFile, cache);
  }
  return files.map((file, index) => ({
    startMs: index * SEGMENT_MS,
    endMs: (index + 1) * SEGMENT_MS,
    text: cache[basename(file)] || '',
  }));
}

async function transcribe(file) {
  return retry(async () => {
    const form = new FormData();
    const bytes = await readFile(file);
    form.append('file', new Blob([bytes]), basename(file));
    form.append('model', ASR_MODEL);
    const response = await fetch(`${API_BASE}/audio/transcriptions`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${API_KEY}` },
      body: form,
    });
    if (!response.ok) throw await httpError('ASR', response);
    const body = await response.json();
    if (!body.text?.trim()) throw new Error('ASR returned empty text');
    return body.text.trim();
  });
}

async function extractAndOcrFrames(video, workDir) {
  const frameDir = join(workDir, 'frames');
  const timestampsFile = join(workDir, 'frame_timestamps.json');
  const cacheFile = join(workDir, 'ocr.json');
  await mkdir(frameDir, { recursive: true });
  let timestamps = await readJsonIfPresent(timestampsFile);
  const existingFrames = (await readdir(frameDir)).filter((name) => name.endsWith('.jpg'));
  if (!timestamps || existingFrames.length === 0) {
    console.log('extracting scene-change and 30-second fallback frames...');
    await rm(frameDir, { recursive: true, force: true });
    await mkdir(frameDir, { recursive: true });
    const result = await run(FFMPEG, [
      '-y', '-i', video,
      '-vf', 'select=eq(n\\,0)+gt(scene\\,0.35)+gte(t-prev_selected_t\\,30),showinfo',
      '-vsync', 'vfr',
      join(frameDir, 'frame_%06d.jpg'),
    ], true);
    timestamps = [...result.stderr.matchAll(/pts_time:([0-9.]+)/g)]
      .map((match) => Math.round(Number(match[1]) * 1000));
    await writeJson(timestampsFile, timestamps);
  }

  const files = (await readdir(frameDir))
    .filter((name) => name.endsWith('.jpg'))
    .sort()
    .map((name) => join(frameDir, name));
  const cache = (await readJsonIfPresent(cacheFile)) || {};
  let previousHash = null;
  const selected = [];
  for (let index = 0; index < files.length; index++) {
    const hash = await differenceHash(files[index]);
    if (previousHash !== null && bitCount(previousHash ^ hash) <= 5) continue;
    previousHash = hash;
    selected.push({
      file: files[index],
      timestampMs: timestamps[index] ?? index * 30_000,
    });
  }

  for (let start = 0; start < selected.length; start += OCR_CONCURRENCY) {
    const batch = selected.slice(start, start + OCR_CONCURRENCY);
    await Promise.all(batch.map(async ({ file }) => {
      const key = basename(file);
      if (cache[key] !== undefined) return;
      const result = await run(TESSERACT, [file, 'stdout', '-l', 'chi_sim+eng'], true);
      cache[key] = result.stdout.trim();
      console.log(`OCR ${key}: ${cache[key].length} chars`);
    }));
    await writeJson(cacheFile, cache);
  }
  return selected.map(({ file, timestampMs }) => ({
    timestampMs,
    text: cache[basename(file)] || '',
  }));
}

async function differenceHash(file) {
  const result = await run(FFMPEG, [
    '-v', 'error', '-i', file,
    '-vf', 'scale=9:8,format=gray',
    '-f', 'rawvideo', '-pix_fmt', 'gray', 'pipe:1',
  ], true, true);
  const pixels = result.stdoutBuffer;
  if (pixels.length < 72) return 0n;
  let hash = 0n;
  for (let y = 0; y < 8; y++) {
    for (let x = 0; x < 8; x++) {
      hash <<= 1n;
      if (pixels[y * 9 + x] > pixels[y * 9 + x + 1]) hash |= 1n;
    }
  }
  return hash;
}

function bitCount(value) {
  let count = 0;
  while (value) {
    count += Number(value & 1n);
    value >>= 1n;
  }
  return count;
}

function mergeSegments(transcripts, frames) {
  const windows = new Map();
  const getWindow = (startMs) => {
    if (!windows.has(startMs)) {
      windows.set(startMs, {
        startMs,
        endMs: startMs + SEGMENT_MS,
        transcript: '',
        ocrTexts: [],
        evidenceFrames: [],
      });
    }
    return windows.get(startMs);
  };
  for (const transcript of transcripts) {
    const window = getWindow(Math.floor(transcript.startMs / SEGMENT_MS) * SEGMENT_MS);
    window.transcript = [window.transcript, transcript.text].filter(Boolean).join('\n');
  }
  for (const frame of frames) {
    const window = getWindow(Math.floor(frame.timestampMs / SEGMENT_MS) * SEGMENT_MS);
    if (frame.text) window.ocrTexts.push(frame.text);
  }
  return [...windows.values()]
    .filter((segment) => segment.startMs < VIDEO_SECONDS * 1000)
    .sort((a, b) => a.startMs - b.startMs);
}

async function buildChunks(segments, cacheFile) {
  const cache = (await readJsonIfPresent(cacheFile)) || {};
  const chunks = [];
  for (let startMs = 0; startMs < VIDEO_SECONDS * 1000; startMs += CHUNK_MS) {
    const rawSegments = segments.filter((segment) =>
      segment.startMs >= startMs && segment.startMs < startMs + CHUNK_MS);
    if (rawSegments.length === 0) continue;
    const key = String(startMs);
    if (!cache[key]?.embedding?.length) {
      const summary = await summarizeChunk(rawSegments);
      const embeddingText = `${summary.segmentSummary}\n${summary.keywords.join(' ')}`;
      cache[key] = {
        startTime: startMs,
        endTime: startMs + CHUNK_MS,
        segmentSummary: summary.segmentSummary,
        keywords: summary.keywords,
        rawSegments,
        embedding: await embed(embeddingText),
      };
      await writeJson(cacheFile, cache);
      console.log(`chunk ${startMs / CHUNK_MS + 1}/${Math.ceil(VIDEO_SECONDS * 1000 / CHUNK_MS)} ready`);
    }
    chunks.push(cache[key]);
  }
  return chunks;
}

async function summarizeChunk(segments) {
  const prompt = [
    '压缩以下五分钟视频片段，保留人物、事件、观点、结论以及重要 OCR 信息。',
    '只返回 JSON：',
    '{"segmentSummary":"不超过 200 字的片段摘要","keywords":["关键词1","关键词2","关键词3"]}',
    '原始片段：',
    JSON.stringify(segments),
  ].join('\n');
  const value = await chatJson(prompt);
  return {
    segmentSummary: String(value.segmentSummary || '').trim(),
    keywords: normalizeTerms(value.keywords),
  };
}

async function draftQuestions(dataset, cacheFile) {
  const cache = (await readJsonIfPresent(cacheFile)) || {};
  const datasetId = dataset.metadata?.datasetId || 'video';
  const exact = [];
  const fuzzy = [];
  const visual = [];
  const textChunks = evenlySpaced(dataset.chunks, DRAFT_COUNTS.exact);

  for (let index = 0; index < textChunks.length; index++) {
    const chunk = textChunks[index];
    const key = `text:${chunk.startTime}`;
    let pair;
    try {
      pair = validateTextPair(cache[key], chunk);
    } catch {
      pair = await draftTextPair(chunk);
      cache[key] = pair;
      await writeJson(cacheFile, cache);
    }
    exact.push(questionCandidate(datasetId, 'exact', index, pair.exact, chunk));
    fuzzy.push(questionCandidate(datasetId, 'fuzzy', index, pair.fuzzy, chunk));
    console.log(`draft text pair ${index + 1}/${textChunks.length} ready`);
  }

  const visualTargets = visualDraftTargets(dataset.chunks, DRAFT_COUNTS.visual);
  if (visualTargets.length < DRAFT_COUNTS.visual) {
    throw new Error(`only ${visualTargets.length} visual draft target(s) have usable OCR text`);
  }
  for (let index = 0; index < visualTargets.length; index++) {
    const { chunk, ordinal } = visualTargets[index];
    const key = `visual:${chunk.startTime}:${ordinal}`;
    let candidate;
    try {
      candidate = validateDraftCandidate(cache[key], chunk, 'visual');
    } catch {
      candidate = await draftVisualQuestion(chunk, visual.slice(-2).map((question) => question.q));
      cache[key] = candidate;
      await writeJson(cacheFile, cache);
    }
    visual.push(questionCandidate(datasetId, 'visual', index, candidate, chunk, 'ocr'));
    console.log(`draft visual question ${index + 1}/${visualTargets.length} ready`);
  }

  return [...exact, ...fuzzy, ...visual];
}

function evenlySpaced(chunks, count) {
  if (chunks.length < count) {
    throw new Error(`need at least ${count} chunks to draft ${count} text question pairs`);
  }
  if (count === 1) return [chunks[Math.floor(chunks.length / 2)]];
  const indexes = [...new Set(Array.from({ length: count }, (_, index) =>
    Math.round(index * (chunks.length - 1) / (count - 1))))];
  if (indexes.length !== count) throw new Error('could not distribute draft questions across chunks');
  return indexes.map((index) => chunks[index]);
}

function visualDraftTargets(chunks, count) {
  const ranked = chunks
    .map((chunk) => ({ chunk, chars: evidenceNormalize(visualText(chunk)).length }))
    .filter((entry) => entry.chars >= 12)
    .sort((left, right) => right.chars - left.chars || left.chunk.startTime - right.chunk.startTime);
  if (ranked.length === 0) return [];
  return Array.from({ length: count }, (_, index) => ({
    chunk: ranked[index % ranked.length].chunk,
    ordinal: Math.floor(index / ranked.length),
  }));
}

async function draftTextPair(chunk) {
  const prompt = [
    '根据下面一个五分钟 VideoChunk 生成两道用于 RAG 召回评测的问题。',
    'exact：问题应包含证据中的明确术语、名称、数字或 API 名。',
    'fuzzy：针对同一片段提出不同问题，但要换一种通俗说法，避免直接照抄核心术语。',
    '问题不能泄露答案，也不能依赖片段外知识。',
    'relevantSecond 必须取自输入中的某个 startSecond。',
    'evidenceText 必须从 transcript 或 OCR 逐字复制 8–24 个连续字符，只作为证据锚点。',
    '即使 ASR 中的专有名词有错字也必须原样照抄，严禁纠错、补字或改写。',
    '只返回 JSON：',
    '{"exact":{"q":"问题","relevantSecond":123,"evidenceText":"原文"},'
      + '"fuzzy":{"q":"问题","relevantSecond":123,"evidenceText":"原文"}}',
    `带时间片段：${JSON.stringify(compactSegments(chunk))}`,
  ].join('\n');
  return draftJson(prompt, (value) => validateTextPair(value, chunk));
}

async function draftVisualQuestion(chunk, previousQuestions) {
  const prompt = [
    '只根据下面 VideoChunk 的 OCR 画面文字，生成一道视觉检索问题。',
    '问题必须依赖 PPT、代码、表格、字幕或画面文字，不得只靠语音摘要回答。',
    '问题不能泄露答案，也不能依赖片段外知识。',
    'relevantSecond 必须取自输入中的某个 startSecond。',
    'evidenceText 必须从 ocrTexts 逐字复制 8–24 个连续字符，只作为证据锚点。',
    '即使 OCR 有错字也必须原样照抄，严禁纠错、补字或改写。',
    previousQuestions.length ? `不要与这些问题重复：${previousQuestions.join('；')}` : '',
    '只返回 JSON：{"q":"问题","relevantSecond":123,"evidenceText":"OCR 原文"}',
    `带时间 OCR：${JSON.stringify(compactSegments(chunk, true))}`,
  ].filter(Boolean).join('\n');
  return draftJson(prompt, (value) => validateDraftCandidate(value, chunk, 'visual'));
}

async function draftJson(prompt, validator) {
  let lastError;
  for (let attempt = 0; attempt < RETRIES; attempt++) {
    try {
      return validator(await chatJson([
        prompt,
        attempt ? '上一次结果未通过原文或时间校验，请严格使用给定的连续原文和时间。' : '',
      ].filter(Boolean).join('\n')));
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

function validateTextPair(value, chunk) {
  if (!value || typeof value !== 'object') throw new Error('text draft must be an object');
  return {
    exact: validateDraftCandidate(value.exact, chunk, 'text'),
    fuzzy: validateDraftCandidate(value.fuzzy, chunk, 'text'),
  };
}

function validateDraftCandidate(value, chunk, channel) {
  if (!value?.q?.trim() || !value?.evidenceText?.trim()) {
    throw new Error(`${channel} draft requires q and evidenceText`);
  }
  const relevantSecond = Number(value.relevantSecond);
  if (!Number.isFinite(relevantSecond)
    || relevantSecond * 1000 < chunk.startTime
    || relevantSecond * 1000 >= chunk.endTime) {
    throw new Error(`${channel} draft relevantSecond is outside the chunk`);
  }
  const source = channel === 'visual'
    ? visualText(chunk)
    : (chunk.rawSegments || []).flatMap((segment) => [
      segment.transcript || '',
      ...(segment.ocrTexts || []),
    ]).join(' ');
  const evidence = evidenceNormalize(value.evidenceText);
  if (evidence.length < 4 || !evidenceNormalize(source).includes(evidence)) {
    throw new Error(`${channel} draft evidenceText is not present in source evidence`);
  }
  return {
    q: String(value.q).trim(),
    relevantSecond,
    evidenceText: String(value.evidenceText).trim().slice(0, 120),
  };
}

function compactSegments(chunk, visualOnly = false) {
  return (chunk.rawSegments || []).map((segment) => ({
    startSecond: Math.round(Number(segment.startMs) / 1000),
    ...(visualOnly ? {} : { transcript: truncate(segment.transcript, 2_000) }),
    ocrTexts: (segment.ocrTexts || []).map((text) => truncate(text, 1_500)).filter(Boolean),
  }));
}

function questionCandidate(datasetId, tag, index, draftValue, chunk, evidenceChannel) {
  return {
    id: `${datasetId}-${tag}-${String(index + 1).padStart(3, '0')}`,
    q: draftValue.q,
    tag,
    relevantSecs: [Math.floor(draftValue.relevantSecond)],
    reviewed: false,
    sourceChunkStartSec: chunk.startTime / 1000,
    evidenceChannel: evidenceChannel || evidenceSource(chunk, draftValue.evidenceText),
    evidenceText: draftValue.evidenceText,
  };
}

function evidenceSource(chunk, evidenceText) {
  const evidence = evidenceNormalize(evidenceText);
  const inAsr = (chunk.rawSegments || []).some((segment) =>
    evidenceNormalize(segment.transcript).includes(evidence));
  const inOcr = (chunk.rawSegments || []).some((segment) =>
    (segment.ocrTexts || []).some((text) => evidenceNormalize(text).includes(evidence)));
  if (inAsr && inOcr) return 'asr+ocr';
  if (inAsr) return 'asr';
  if (inOcr) return 'ocr';
  throw new Error('draft evidence source cannot be resolved to ASR or OCR');
}

function truncate(value, limit) {
  const text = String(value || '').trim();
  return text.length > limit ? text.slice(0, limit) : text;
}

function evidenceNormalize(value) {
  return String(value || '').toLowerCase().replace(/[\p{P}\p{S}\s]+/gu, '');
}

async function enrichQuestions(questions, cacheFile) {
  const cache = (await readJsonIfPresent(cacheFile)) || {};
  const result = [];
  for (let index = 0; index < questions.length; index++) {
    const question = questions[index];
    const key = createHash('sha256').update(JSON.stringify({
      q: question.q,
      tag: question.tag,
      relevantSecs: questionRelevantSecs(question),
    })).digest('hex');
    if (!cache[key]?.rawEmbedding?.length || !cache[key]?.semanticEmbedding?.length) {
      const intent = await planRetrieval(question.q);
      cache[key] = {
        ...question,
        intent,
        rawEmbedding: await embed(question.q),
        semanticEmbedding: await embed(intent.semanticQuery || question.q),
      };
      await writeJson(cacheFile, cache);
      console.log(`question ${index + 1}/${questions.length} ready`);
    }
    result.push(cache[key]);
  }
  return result;
}

async function planRetrieval(goal) {
  const prompt = [
    '你是 Video Agent 的检索规划器。把用户目标改写成适合检索长视频证据的查询。',
    'semanticQuery 用于检索语音、摘要和上下文语义。',
    'keywords 保留人物、概念、事件和专有名词。',
    'visualKeywords 只保留可能出现在字幕、PPT、代码或画面文字中的词；没有则返回空数组。',
    '不回答用户问题，只返回 JSON：',
    '{"semanticQuery":"完整、明确的检索语句","keywords":["关键词"],"visualKeywords":["画面文字关键词"]}',
    `用户目标：\n${goal}`,
  ].join('\n');
  const value = await chatJson(prompt);
  return {
    semanticQuery: String(value.semanticQuery || goal).trim(),
    keywords: normalizeTerms(value.keywords),
    visualKeywords: normalizeTerms(value.visualKeywords),
  };
}

async function chatJson(prompt) {
  return retry(async () => {
    const response = await fetch(`${API_BASE}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({
        model: LLM_MODEL,
        messages: [
          { role: 'system', content: '你必须严格基于输入工作，无法确认的信息不得编造。' },
          { role: 'user', content: prompt },
        ],
        temperature: 0,
        stream: false,
      }),
    });
    if (!response.ok) throw await httpError('LLM', response);
    const body = await response.json();
    const text = body.choices?.[0]?.message?.content || '';
    const start = text.indexOf('{');
    const end = text.lastIndexOf('}');
    if (start < 0 || end <= start) throw new Error('LLM did not return a JSON object');
    return JSON.parse(text.slice(start, end + 1));
  });
}

async function embed(text) {
  return retry(async () => {
    const response = await fetch(`${API_BASE}/embeddings`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${API_KEY}`,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ model: EMBEDDING_MODEL, input: text }),
    });
    if (!response.ok) throw await httpError('Embedding', response);
    const body = await response.json();
    const vector = body.data?.[0]?.embedding;
    if (!Array.isArray(vector) || vector.length === 0) throw new Error('Embedding returned no vector');
    return vector;
  });
}

async function retry(work) {
  let lastError;
  for (let attempt = 0; attempt < RETRIES; attempt++) {
    try {
      return await work();
    } catch (error) {
      lastError = error;
      if (attempt === RETRIES - 1 || error.retryable === false) break;
      await sleep(1_000 * (2 ** attempt));
    }
  }
  throw lastError;
}

async function httpError(stage, response) {
  const body = (await response.text()).slice(0, 500).replace(/sk-[a-z0-9]+/gi, '[redacted]');
  const error = new Error(`${stage} HTTP ${response.status}: ${body}`);
  error.retryable = response.status === 408 || response.status === 429 || response.status >= 500;
  return error;
}

async function compare(datasetFile, resultsFile) {
  const dataset = JSON.parse(await readFile(datasetFile, 'utf8'));
  const modes = {
    oldHybrid: [],
    currentFormulaOnly: [],
    currentFull: [],
    vectorOnly: [],
    oldKeywordOnly: [],
  };

  for (const question of dataset.questions) {
    const expected = expectedChunks(question, dataset.chunks);
    if (expected.length === 0) throw new Error(`no expected chunk for ${question.q}`);
    const relevantStarts = new Set(expected.map((chunk) => chunk.startTime));
    const fallback = fallbackTerms(question.q);
    const intent = question.intent || {
      semanticQuery: question.q,
      keywords: fallback,
      visualKeywords: fallback,
    };
    const scoreSets = {
      oldHybrid: dataset.chunks.map((chunk) =>
        cosine(question.rawEmbedding, chunk.embedding) * 0.7
        + oldKeywordScore(question.q, chunk) * 0.3),
      currentFormulaOnly: dataset.chunks.map((chunk) => currentScore(
        { keywords: fallback, visualKeywords: fallback },
        question.rawEmbedding,
        chunk,
      )),
      currentFull: dataset.chunks.map((chunk) => currentScore(
        intent,
        question.semanticEmbedding,
        chunk,
      )),
      vectorOnly: dataset.chunks.map((chunk) => cosine(question.rawEmbedding, chunk.embedding)),
      oldKeywordOnly: dataset.chunks.map((chunk) => oldKeywordScore(question.q, chunk)),
    };

    for (const [mode, scores] of Object.entries(scoreSets)) {
      const ranked = dataset.chunks
        .map((chunk, index) => ({ chunk, score: scores[index] }))
        .sort((left, right) => right.score - left.score || left.chunk.startTime - right.chunk.startTime);
      const relevantPositions = ranked
        .map((entry, index) => relevantStarts.has(entry.chunk.startTime) ? index : -1)
        .filter((index) => index >= 0);
      const position = relevantPositions.length ? Math.min(...relevantPositions) : -1;
      const recalledAt3 = relevantPositions.filter((index) => index < 3).length / expected.length;
      modes[mode].push({
        id: question.id || null,
        q: question.q,
        tag: question.tag,
        expectedStartSec: expected[0].startTime / 1000,
        relevantStartSecs: expected.map((chunk) => chunk.startTime / 1000),
        rank: position + 1,
        hitAt1: position === 0,
        hitAt3: position >= 0 && position < 3,
        recallAt3: recalledAt3,
        rrAt3: position >= 0 && position < 3 ? 1 / (position + 1) : 0,
        top3StartSec: ranked.slice(0, 3).map((entry) => entry.chunk.startTime / 1000),
      });
    }
  }

  const summary = Object.fromEntries(
    Object.entries(modes).map(([mode, rows]) => [mode, summarize(rows)]),
  );
  const uplift = {
    currentFormulaOnlyVsOld: compareModes(modes.oldHybrid, modes.currentFormulaOnly),
    currentFullVsOld: compareModes(modes.oldHybrid, modes.currentFull),
  };
  const output = {
    metadata: {
      ...dataset.metadata,
      comparedAt: new Date().toISOString(),
      historicalFormula: '0.7*cosine(raw query, chunk embedding) + 0.3*historical keyword score',
      currentFormula: '0.6*semantic + 0.25*text terms + 0.15*OCR visual terms',
      currentFormulaOnlyIntent: 'raw query embedding + production fallback terms',
      currentFullIntent: 'LLM semanticQuery/keywords/visualKeywords',
    },
    summary,
    uplift,
    detail: modes,
  };
  await mkdir(dirname(resultsFile), { recursive: true });
  await writeJson(resultsFile, output);
  console.log(JSON.stringify({ summary, uplift }, null, 2));
}

function currentScore(intent, queryEmbedding, chunk) {
  return cosine(queryEmbedding, chunk.embedding) * 0.6
    + termScore(intent.keywords, searchableText(chunk)) * 0.25
    + termScore(intent.visualKeywords, visualText(chunk)) * 0.15;
}

function expectedChunks(question, chunks) {
  const byStart = new Map();
  for (const second of questionRelevantSecs(question)) {
    const chunk = chunks.find((candidate) =>
      second * 1000 >= candidate.startTime && second * 1000 < candidate.endTime);
    if (chunk) byStart.set(chunk.startTime, chunk);
  }
  return [...byStart.values()].sort((left, right) => left.startTime - right.startTime);
}

function searchableText(chunk) {
  return [
    chunk.segmentSummary,
    ...(chunk.keywords || []),
    ...(chunk.rawSegments || []).map((segment) => segment.transcript || ''),
  ].join(' ');
}

function visualText(chunk) {
  return (chunk.rawSegments || [])
    .flatMap((segment) => segment.ocrTexts || [])
    .filter(Boolean)
    .join(' ');
}

function termScore(terms, content) {
  const normalizedContent = currentNormalize(content);
  const normalizedTerms = [...new Set((terms || [])
    .map(currentNormalize)
    .filter(Boolean))];
  if (normalizedTerms.length === 0) return 0;
  return normalizedTerms.filter((term) => normalizedContent.includes(term)).length / normalizedTerms.length;
}

function oldKeywordScore(query, chunk) {
  const normalizedQuery = historicalNormalize(query);
  const keywords = chunk.keywords || [];
  if (keywords.length === 0) return 0;
  const matched = keywords.filter((keyword) =>
    keyword?.trim() && normalizedQuery.includes(historicalNormalize(keyword))).length;
  return matched / keywords.length;
}

function cosine(left, right) {
  if (!left?.length || !right?.length || left.length !== right.length) return 0;
  let dot = 0;
  let leftLength = 0;
  let rightLength = 0;
  for (let index = 0; index < left.length; index++) {
    dot += left[index] * right[index];
    leftLength += left[index] * left[index];
    rightLength += right[index] * right[index];
  }
  return leftLength && rightLength ? dot / Math.sqrt(leftLength * rightLength) : 0;
}

function historicalNormalize(value) {
  return String(value || '').toLowerCase().replace(/[\p{P}\p{S}\s]+/gu, '');
}

function currentNormalize(value) {
  return String(value || '').toLowerCase().replace(/\s+/g, '');
}

function fallbackTerms(query) {
  const terms = String(query || '').trim()
    .split(/[\s，。！？、,.;:：；!?]+/)
    .map((term) => term.trim())
    .filter((term) => term.length >= 2)
    .filter((term, index, values) => values.indexOf(term) === index)
    .slice(0, 8);
  return terms.length ? terms : [String(query || '').trim()].filter(Boolean);
}

function summarize(rows) {
  const byTag = (tag) => rows.filter((row) => row.tag === tag);
  const metrics = (values) => ({
    n: values.length,
    hitAt1: ratio(values.filter((row) => row.hitAt1).length, values.length),
    hitAt3: ratio(values.filter((row) => row.hitAt3).length, values.length),
    recallAt3: ratio(values.reduce(
      (sum, row) => sum + (row.recallAt3 ?? Number(row.hitAt3)), 0), values.length),
    mrrAt3: ratio(values.reduce((sum, row) => sum + row.rrAt3, 0), values.length),
  });
  return {
    ...metrics(rows),
    exact: metrics(byTag('exact')),
    fuzzy: metrics(byTag('fuzzy')),
    visual: metrics(byTag('visual')),
  };
}

function compareModes(baseline, candidate) {
  const baselineSummary = summarize(baseline);
  const candidateSummary = summarize(candidate);
  return {
    hitAt1AbsolutePoints: points(candidateSummary.hitAt1 - baselineSummary.hitAt1),
    hitAt3AbsolutePoints: points(candidateSummary.hitAt3 - baselineSummary.hitAt3),
    recallAt3AbsolutePoints: points(candidateSummary.recallAt3 - baselineSummary.recallAt3),
    recallAt3RelativePercent: relativePercent(candidateSummary.recallAt3, baselineSummary.recallAt3),
    hitAt3RelativePercent: relativePercent(candidateSummary.hitAt3, baselineSummary.hitAt3),
    mrrAt3Absolute: round(candidateSummary.mrrAt3 - baselineSummary.mrrAt3, 4),
    exactHitAt3Points: points(candidateSummary.exact.hitAt3 - baselineSummary.exact.hitAt3),
    fuzzyHitAt3Points: points(candidateSummary.fuzzy.hitAt3 - baselineSummary.fuzzy.hitAt3),
    gainedQueries: candidate.filter((row, index) => row.hitAt3 && !baseline[index].hitAt3).map((row) => row.q),
    lostQueries: candidate.filter((row, index) => !row.hitAt3 && baseline[index].hitAt3).map((row) => row.q),
    pairedBootstrap95: bootstrapDelta(baseline, candidate),
  };
}

function bootstrapDelta(baseline, candidate) {
  let seed = 0x5eed1234;
  const random = () => {
    seed = (1664525 * seed + 1013904223) >>> 0;
    return seed / 0x1_0000_0000;
  };
  const hitDeltas = [];
  const recallDeltas = [];
  const mrrDeltas = [];
  for (let sample = 0; sample < 10_000; sample++) {
    let hitDelta = 0;
    let recallDelta = 0;
    let mrrDelta = 0;
    for (let draw = 0; draw < baseline.length; draw++) {
      const index = Math.floor(random() * baseline.length);
      hitDelta += Number(candidate[index].hitAt3) - Number(baseline[index].hitAt3);
      recallDelta += (candidate[index].recallAt3 ?? Number(candidate[index].hitAt3))
        - (baseline[index].recallAt3 ?? Number(baseline[index].hitAt3));
      mrrDelta += candidate[index].rrAt3 - baseline[index].rrAt3;
    }
    hitDeltas.push(hitDelta / baseline.length);
    recallDeltas.push(recallDelta / baseline.length);
    mrrDeltas.push(mrrDelta / baseline.length);
  }
  hitDeltas.sort((a, b) => a - b);
  recallDeltas.sort((a, b) => a - b);
  mrrDeltas.sort((a, b) => a - b);
  return {
    hitAt3Points: [points(percentile(hitDeltas, 0.025)), points(percentile(hitDeltas, 0.975))],
    recallAt3Points: [points(percentile(recallDeltas, 0.025)), points(percentile(recallDeltas, 0.975))],
    mrrAt3: [round(percentile(mrrDeltas, 0.025), 4), round(percentile(mrrDeltas, 0.975), 4)],
  };
}

function percentile(sorted, quantile) {
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * quantile))];
}

function ratio(value, total) {
  return total ? round(value / total, 4) : 0;
}

function points(value) {
  return round(value * 100, 2);
}

function relativePercent(value, baseline) {
  return baseline ? round((value / baseline - 1) * 100, 2) : null;
}

function round(value, digits) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

function normalizeTerms(values) {
  if (!Array.isArray(values)) return [];
  return [...new Set(values.map((value) => String(value || '').trim()).filter(Boolean))];
}

function validateQuestions(questions) {
  if (!Array.isArray(questions) || questions.length === 0) throw new Error('questions must be a non-empty array');
  for (const question of questions) {
    const relevantSecs = questionRelevantSecs(question);
    if (!question.q || !['exact', 'fuzzy', 'visual'].includes(question.tag) || relevantSecs.length === 0
      || relevantSecs.some((second) => !Number.isFinite(second) || second < 0)) {
      throw new Error(`invalid question: ${JSON.stringify(question)}`);
    }
    if (question.reviewed !== true) {
      throw new Error(`question must be human reviewed before enrichment: ${question.id || question.q}`);
    }
  }
}

function questionRelevantSecs(question) {
  if (Array.isArray(question.relevantSecs)) {
    return [...new Set(question.relevantSecs.map(Number))];
  }
  return Number.isFinite(question.expectSec) ? [question.expectSec] : [];
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`${name} must be a positive integer`);
  }
  return parsed;
}

async function readJsonIfPresent(file) {
  try {
    return JSON.parse(await readFile(file, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

async function writeJson(file, value) {
  await writeFile(file, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

async function exists(file) {
  try {
    await access(file);
    return true;
  } catch {
    return false;
  }
}

async function run(executable, commandArgs, capture = false, binary = false) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(executable, commandArgs, {
      stdio: capture ? ['ignore', 'pipe', 'pipe'] : 'inherit',
      windowsHide: true,
    });
    const stdout = [];
    const stderr = [];
    if (capture) {
      child.stdout.on('data', (chunk) => stdout.push(chunk));
      child.stderr.on('data', (chunk) => stderr.push(chunk));
    }
    child.on('error', rejectPromise);
    child.on('close', (code) => {
      const stdoutBuffer = Buffer.concat(stdout);
      const stderrBuffer = Buffer.concat(stderr);
      if (code !== 0) {
        rejectPromise(new Error(`${executable} exited ${code}: ${stderrBuffer.toString('utf8').slice(-2_000)}`));
        return;
      }
      resolvePromise({
        stdout: binary ? '' : stdoutBuffer.toString('utf8'),
        stderr: stderrBuffer.toString('utf8'),
        stdoutBuffer,
      });
    });
  });
}

function sleep(ms) {
  return new Promise((resolvePromise) => setTimeout(resolvePromise, ms));
}
