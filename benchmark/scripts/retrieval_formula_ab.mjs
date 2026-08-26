#!/usr/bin/env node
/**
 * Rebuild the 60-minute retrieval corpus and compare the historical and current
 * retrieval formulas on exactly the same chunks, labels, and embeddings.
 *
 * Prepare (requires SILICONFLOW_API_KEY, ffmpeg, and tesseract):
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

const [command, ...args] = process.argv.slice(2);

if (command === 'prepare') {
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
  console.error('usage: retrieval_formula_ab.mjs prepare <video> <questions.json> <dataset.json>');
  console.error('   or: retrieval_formula_ab.mjs compare <dataset.json> <results.json>');
  process.exit(1);
}

async function prepare(videoFile, questionsFile, datasetFile) {
  if (!API_KEY) throw new Error('SILICONFLOW_API_KEY is required for prepare');
  await access(videoFile);
  const questions = JSON.parse(await readFile(questionsFile, 'utf8'));
  validateQuestions(questions);

  const workDir = resolve(
    process.env.DOV_RETRIEVAL_WORK || join(tmpdir(), 'dovideo-retrieval-ab', 'prepared'),
  );
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

  const questionCacheFile = join(workDir, 'questions_enriched.json');
  const enrichedQuestions = await enrichQuestions(questions, questionCacheFile);
  console.log(`questions ready: ${enrichedQuestions.length}`);

  const dataset = {
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
    questions: enrichedQuestions,
  };
  await mkdir(dirname(datasetFile), { recursive: true });
  await writeJson(datasetFile, dataset);
  console.log(`dataset written: ${datasetFile}`);
}

async function ensureClippedVideo(source, target) {
  if (await exists(target)) return;
  console.log('clipping the first 60 minutes...');
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

async function enrichQuestions(questions, cacheFile) {
  const cache = (await readJsonIfPresent(cacheFile)) || {};
  const result = [];
  for (let index = 0; index < questions.length; index++) {
    const question = questions[index];
    const key = String(index);
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
    if (!question.q || !question.tag || relevantSecs.length === 0
      || relevantSecs.some((second) => !Number.isFinite(second) || second < 0)) {
      throw new Error(`invalid question: ${JSON.stringify(question)}`);
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
