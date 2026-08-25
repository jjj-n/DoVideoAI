#!/usr/bin/env node
/**
 * 证据三层校验评测
 * 输入（由 dump_checkpoints 导出）：
 *   evidence_input.json: { segments: [{startMs,endMs,transcript,ocrTexts}], evidences: [{timestampMs,source,content}], conclusions: [..], critiquePassed: bool }
 * 层1 时间戳覆盖：存在 segment [startMs,endMs) 包含 timestampMs（复刻 timestampCovered）
 * 层2 文本存在：时间戳命中段内 ASR/OCR 文本匹配（包含 or bigram覆盖>=0.5，复刻 supported）
 * 层3 claim 一致：Critic LLM 软校验（记录 critiquePassed + unsupportedClaims 数量，另人工抽检）
 * 用法: node evidence_eval.mjs evidence_input.json
 */
import fs from 'node:fs';

const [,, file] = process.argv;
if (!file) { console.error('usage: node evidence_eval.mjs evidence_input.json'); process.exit(1); }
const { segments, evidences, conclusions, critiquePassed, unsupportedClaims } = JSON.parse(fs.readFileSync(file, 'utf8'));

const normalize = (v) => (v || '').toLowerCase().replace(/[\p{P}\p{S}\s]+/gu, '');
const bigrams = (s) => { const r = new Set(); for (let i = 0; i < s.length - 1; i++) r.add(s.slice(i, i + 2)); return r; };

function sourceText(seg, source) {
  const s = (source || '').toUpperCase();
  const asr = seg.transcript || '', ocr = (seg.ocrTexts || []).join(' ');
  if (s.includes('ASR') && s.includes('OCR')) return asr + ' ' + ocr;
  if (s.includes('ASR')) return asr;
  return ocr;
}
function textMatches(ev, cand) {
  const a = normalize(ev), b = normalize(cand);
  if (!a || !b) return false;
  if (b.includes(a) || a.includes(b)) return true;
  if (a.length < 4 || b.length < 4) return false;
  const ba = bigrams(a), bb = bigrams(b);
  let ov = 0; for (const g of ba) if (bb.has(g)) ov++;
  return ov / ba.size >= 0.5;
}
function layer1(ev) { return segments.some(s => ev.timestampMs >= s.startMs && ev.timestampMs < s.endMs); }
function layer2(ev) {
  const src = (ev.source || '').toUpperCase();
  if (!src.includes('ASR') && !src.includes('OCR')) return false;
  if (!normalize(ev.content)) return false;
  return segments
    .filter(s => ev.timestampMs >= s.startMs && ev.timestampMs < s.endMs)
    .some(s => textMatches(ev.content, sourceText(s, src)));
}

const detail = evidences.map(ev => ({
  timestampMs: ev.timestampMs, source: ev.source,
  l1: layer1(ev), l2: layer2(ev),
  inRange: true, // 层内含：时间戳是否落在视频总时长内（由 dump 时校验）
}));
const n = detail.length || 1;
const l1Pass = detail.filter(d => d.l1).length;
const l2Pass = detail.filter(d => d.l1 && d.l2).length;
const summary = {
  evidences: evidences.length,
  conclusions: conclusions.length,
  layer1Timestamp: `${l1Pass}/${evidences.length} (${(l1Pass / n * 100).toFixed(0)}%)`,
  layer2Text: `${l2Pass}/${evidences.length} (${(l2Pass / n * 100).toFixed(0)}%)`,
  layer3CriticSoft: `critiquePassed=${critiquePassed}, unsupportedClaims=${(unsupportedClaims || []).length}`,
  citationsPerConclusion: +(evidences.length / Math.max(conclusions.length, 1)).toFixed(2),
};
fs.writeFileSync('evidence_results.json', JSON.stringify({ summary, detail }, null, 2));
console.log(JSON.stringify(summary, null, 2));
