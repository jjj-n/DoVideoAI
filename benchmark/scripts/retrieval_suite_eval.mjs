#!/usr/bin/env node
/** Validate and aggregate retrieval results across independently labeled videos. */
import { access, mkdir, readFile, writeFile } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';

const [command, manifestArg, ...rest] = process.argv.slice(2);
const outputArg = command === 'aggregate' ? rest[0] : null;
const flags = command === 'aggregate' ? rest.slice(1) : rest;
if (!['validate', 'aggregate'].includes(command) || !manifestArg
  || (command === 'aggregate' && !outputArg)) {
  usage();
}

const manifestFile = resolve(manifestArg);
const manifestDir = dirname(manifestFile);
const suite = JSON.parse(await readFile(manifestFile, 'utf8'));
const audit = await auditSuite(suite, manifestDir);

if (command === 'validate') {
  console.log(JSON.stringify(publicAudit(audit), null, 2));
  if (audit.errors.length > 0 || (flags.includes('--strict') && !audit.coverage.publishable)) {
    process.exitCode = 1;
  }
} else {
  if (audit.errors.length > 0) {
    console.error(JSON.stringify(publicAudit(audit), null, 2));
    process.exitCode = 1;
  } else {
    const outputFile = resolve(outputArg);
    const aggregated = aggregateSuite(suite, audit);
    await mkdir(dirname(outputFile), { recursive: true });
    await writeFile(outputFile, `${JSON.stringify(aggregated, null, 2)}\n`, 'utf8');
    console.log(JSON.stringify({
      outputFile,
      coverage: aggregated.coverage,
      primary: aggregated.summary[aggregated.metadata.primaryMode],
    }, null, 2));
  }
}

function usage() {
  console.error('usage: retrieval_suite_eval.mjs validate <suite.json> [--strict]');
  console.error('   or: retrieval_suite_eval.mjs aggregate <suite.json> <output.json>');
  process.exit(1);
}

async function auditSuite(manifest, baseDir) {
  const errors = [];
  const warnings = [];
  const loaded = [];
  if (manifest.schemaVersion !== 1) errors.push('schemaVersion must be 1');
  if (!manifest.suiteId) errors.push('suiteId is required');
  if (!manifest.primaryMode) errors.push('primaryMode is required');
  if (!Array.isArray(manifest.samples)) errors.push('samples must be an array');

  const ids = new Set();
  for (const sample of manifest.samples || []) {
    if (!sample.id) {
      errors.push('every sample requires an id');
      continue;
    }
    if (ids.has(sample.id)) errors.push(`duplicate sample id: ${sample.id}`);
    ids.add(sample.id);
    if (sample.status !== 'ready') continue;
    try {
      loaded.push(await loadSample(sample, baseDir, manifest));
    } catch (error) {
      errors.push(`${sample.id}: ${error.message}`);
    }
  }

  const primary = loaded.filter((entry) => entry.sample.role === 'primary');
  if (primary.length === 0) warnings.push('no ready primary retrieval sample');
  const coverage = calculateCoverage(manifest, primary);
  warnings.push(...coverage.warnings);
  return { errors, warnings, coverage, loaded };
}

async function loadSample(sample, baseDir, suite) {
  if (!sample.questionsFile || !sample.resultsFile) {
    throw new Error('ready sample requires questionsFile and resultsFile');
  }
  const questionsFile = resolve(baseDir, sample.questionsFile);
  const resultsFile = resolve(baseDir, sample.resultsFile);
  await Promise.all([access(questionsFile), access(resultsFile)]);
  const [questions, results] = await Promise.all([
    readJson(questionsFile),
    readJson(resultsFile),
  ]);
  if (!Array.isArray(questions) || questions.length === 0) throw new Error('questions must be non-empty');
  if (!results.detail || typeof results.detail !== 'object') throw new Error('results.detail is required');
  if (!results.detail[suite.primaryMode]) {
    throw new Error(`results missing primary mode ${suite.primaryMode}`);
  }
  if (sample.questionCount !== questions.length) {
    throw new Error(`questionCount=${sample.questionCount}, file contains ${questions.length}`);
  }
  const normalizedQuestions = questions.map((question, index) => validateQuestion(question, index));
  const duplicateQueries = duplicates(normalizedQuestions.map((question) => question.q));
  if (duplicateQueries.length) throw new Error(`duplicate queries: ${duplicateQueries.join(', ')}`);

  const modeNames = Object.keys(results.detail);
  for (const mode of modeNames) {
    const rows = results.detail[mode];
    if (!Array.isArray(rows) || rows.length !== questions.length) {
      throw new Error(`${mode} has ${rows?.length ?? 'invalid'} rows; expected ${questions.length}`);
    }
  }
  if (results.metadata?.chunkMs && results.metadata.chunkMs !== suite.chunkSeconds * 1000) {
    throw new Error(`chunkMs=${results.metadata.chunkMs}, expected ${suite.chunkSeconds * 1000}`);
  }
  return {
    sample,
    questions: normalizedQuestions,
    results,
    modeNames,
    questionsFile,
    resultsFile,
  };
}

function validateQuestion(question, index) {
  if (!question.q || !question.tag) throw new Error(`question ${index + 1} requires q and tag`);
  const relevantSecs = Array.isArray(question.relevantSecs)
    ? question.relevantSecs.map(Number)
    : [Number(question.expectSec)];
  if (relevantSecs.length === 0 || relevantSecs.some((value) => !Number.isFinite(value) || value < 0)) {
    throw new Error(`question ${index + 1} has invalid relevant time`);
  }
  return {
    ...question,
    id: question.id || `q-${String(index + 1).padStart(3, '0')}`,
    reviewed: question.reviewed === true,
    relevantSecs,
  };
}

function calculateCoverage(suite, primary) {
  const targets = suite.targets || {};
  const validSamples = primary.filter((entry) =>
    entry.sample.durationSeconds >= targets.minimumDurationSeconds
    && entry.sample.chunkCount >= targets.minimumChunksPerSample
    && entry.questions.length >= targets.minimumQuestionsPerSample);
  const questions = validSamples.flatMap((entry) => entry.questions);
  const tagCounts = countBy(questions.map((question) => question.tag));
  const tagRatios = Object.fromEntries(Object.keys(targets.tagRatios || {}).map((tag) => [
    tag,
    ratio(tagCounts[tag] || 0, questions.length),
  ]));
  const reviewedQuestions = questions.filter((question) => question.reviewed).length;
  const splits = new Set(validSamples.map((entry) => entry.sample.split));
  const warnings = [];
  const sampleGap = Math.max(0, (targets.primarySamples || 0) - validSamples.length);
  const questionGap = Math.max(0, (targets.totalQuestions || 0) - questions.length);
  if (sampleGap) warnings.push(`need ${sampleGap} more qualifying primary video(s)`);
  if (questionGap) warnings.push(`need ${questionGap} more reviewed question(s)`);
  if (reviewedQuestions !== questions.length) {
    warnings.push(`${questions.length - reviewedQuestions} question(s) are not marked reviewed`);
  }
  if (targets.devTestSplitRequired && (!splits.has('dev') || !splits.has('test'))) {
    warnings.push('both dev and test samples are required before publishing');
  }
  for (const [tag, target] of Object.entries(targets.tagRatios || {})) {
    if (Math.abs((tagRatios[tag] || 0) - target) > 0.1) {
      warnings.push(`${tag} ratio ${tagRatios[tag] || 0} is outside target ${target} +/- 0.1`);
    }
  }
  const publishable = sampleGap === 0
    && questionGap === 0
    && reviewedQuestions === questions.length
    && (!targets.devTestSplitRequired || (splits.has('dev') && splits.has('test')))
    && Object.entries(targets.tagRatios || {}).every(([tag, target]) =>
      Math.abs((tagRatios[tag] || 0) - target) <= 0.1);
  return {
    publishable,
    readyPrimarySamples: validSamples.length,
    targetPrimarySamples: targets.primarySamples || null,
    reviewedQuestions,
    targetQuestions: targets.totalQuestions || null,
    tagCounts,
    tagRatios,
    splits: [...splits].sort(),
    warnings,
  };
}

function aggregateSuite(suite, audit) {
  const primarySamples = audit.loaded.filter((entry) => entry.sample.role === 'primary');
  const commonModes = primarySamples.length
    ? primarySamples.map((entry) => new Set(entry.modeNames)).reduce(intersection)
    : new Set();
  const rowsByMode = {};
  for (const mode of commonModes) {
    rowsByMode[mode] = primarySamples.flatMap((entry) =>
      entry.results.detail[mode].map((row, index) => ({
        ...row,
        id: row.id || entry.questions[index].id,
        sampleId: entry.sample.id,
        reviewed: entry.questions[index].reviewed,
      })));
  }
  const summary = Object.fromEntries(Object.entries(rowsByMode).map(([mode, rows]) => [
    mode,
    summarize(rows),
  ]));
  const primaryRows = rowsByMode[suite.primaryMode] || [];
  const comparisons = Object.fromEntries(Object.entries(rowsByMode)
    .filter(([mode]) => mode !== suite.primaryMode)
    .map(([mode, rows]) => [`${suite.primaryMode}Vs${mode}`, compareRows(rows, primaryRows)]));
  const perSample = Object.fromEntries(primarySamples.map((entry) => [
    entry.sample.id,
    Object.fromEntries([...commonModes].map((mode) => [mode, summarize(entry.results.detail[mode])])),
  ]));
  return {
    metadata: {
      suiteId: suite.suiteId,
      status: audit.coverage.publishable ? 'publishable' : 'provisional',
      generatedAt: new Date().toISOString(),
      primaryMode: suite.primaryMode,
      primaryMetric: suite.primaryMetric,
      scoringUnit: `${suite.chunkSeconds}-second VideoChunk`,
    },
    coverage: audit.coverage,
    summary,
    comparisons,
    perSample,
    caveats: audit.warnings,
  };
}

function summarize(rows) {
  const tags = [...new Set(rows.map((row) => row.tag))].sort();
  const metrics = (values) => ({
    n: values.length,
    hitAt1: ratio(values.filter((row) => row.hitAt1).length, values.length),
    hitAt3: ratio(values.filter((row) => row.hitAt3).length, values.length),
    recallAt3: ratio(values.reduce((sum, row) =>
      sum + (row.recallAt3 ?? Number(row.hitAt3)), 0), values.length),
    mrrAt3: ratio(values.reduce((sum, row) => sum + row.rrAt3, 0), values.length),
  });
  return {
    ...metrics(rows),
    byTag: Object.fromEntries(tags.map((tag) => [tag, metrics(rows.filter((row) => row.tag === tag))])),
  };
}

function compareRows(baseline, candidate) {
  if (baseline.length !== candidate.length) throw new Error('paired modes have different row counts');
  const base = summarize(baseline);
  const next = summarize(candidate);
  return {
    recallAt3AbsolutePoints: points(next.recallAt3 - base.recallAt3),
    hitAt3AbsolutePoints: points(next.hitAt3 - base.hitAt3),
    mrrAt3Absolute: round(next.mrrAt3 - base.mrrAt3, 4),
    gainedQueries: candidate.filter((row, index) => row.hitAt3 && !baseline[index].hitAt3)
      .map((row) => `${row.sampleId}:${row.id}`),
    lostQueries: candidate.filter((row, index) => !row.hitAt3 && baseline[index].hitAt3)
      .map((row) => `${row.sampleId}:${row.id}`),
    pairedBootstrap95: bootstrapDelta(baseline, candidate),
  };
}

function bootstrapDelta(baseline, candidate) {
  let seed = 0x5eed1234;
  const random = () => {
    seed = (1664525 * seed + 1013904223) >>> 0;
    return seed / 0x1_0000_0000;
  };
  const recallDeltas = [];
  const mrrDeltas = [];
  for (let sample = 0; sample < 10_000; sample++) {
    let recallDelta = 0;
    let mrrDelta = 0;
    for (let draw = 0; draw < baseline.length; draw++) {
      const index = Math.floor(random() * baseline.length);
      recallDelta += (candidate[index].recallAt3 ?? Number(candidate[index].hitAt3))
        - (baseline[index].recallAt3 ?? Number(baseline[index].hitAt3));
      mrrDelta += candidate[index].rrAt3 - baseline[index].rrAt3;
    }
    recallDeltas.push(recallDelta / baseline.length);
    mrrDeltas.push(mrrDelta / baseline.length);
  }
  recallDeltas.sort((left, right) => left - right);
  mrrDeltas.sort((left, right) => left - right);
  return {
    recallAt3Points: [
      points(percentile(recallDeltas, 0.025)),
      points(percentile(recallDeltas, 0.975)),
    ],
    mrrAt3: [
      round(percentile(mrrDeltas, 0.025), 4),
      round(percentile(mrrDeltas, 0.975), 4),
    ],
  };
}

function publicAudit(audit) {
  return {
    errors: audit.errors,
    warnings: audit.warnings,
    coverage: audit.coverage,
    readySamples: audit.loaded.map((entry) => entry.sample.id),
  };
}

function intersection(left, right) {
  return new Set([...left].filter((value) => right.has(value)));
}

function duplicates(values) {
  const seen = new Set();
  const duplicate = new Set();
  for (const value of values) {
    if (seen.has(value)) duplicate.add(value);
    seen.add(value);
  }
  return [...duplicate];
}

function countBy(values) {
  return values.reduce((counts, value) => ({
    ...counts,
    [value]: (counts[value] || 0) + 1,
  }), {});
}

function percentile(sorted, quantile) {
  return sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * quantile))] || 0;
}

function ratio(value, total) {
  return total ? round(value / total, 4) : 0;
}

function points(value) {
  return round(value * 100, 2);
}

function round(value, digits) {
  const factor = 10 ** digits;
  return Math.round(value * factor) / factor;
}

async function readJson(file) {
  return JSON.parse(await readFile(file, 'utf8'));
}
