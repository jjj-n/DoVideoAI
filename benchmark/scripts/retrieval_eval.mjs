#!/usr/bin/env node
/**
 * 检索质量评测：混合检索 vs 纯向量 vs 纯关键词
 * 数据源：Redis checkpoint 中的 chunks（与项目运行时完全一致的数据）
 * 打分公式复刻 LongVideoContextService：
 *   混合 = 0.7*cosine(queryEmb, chunkEmb) + 0.3*keywordScore(query, chunk)
 *   keywordScore = 命中的chunk关键词数 / chunk关键词总数（normalize后包含匹配）
 * 指标：Hit@3 / MRR@3（期望chunk = 覆盖标注时间点的chunk）
 * 用法: node retrieval_eval.mjs <questions.json> <chunks.json> [apiKey]
 *   questions.json: [{q, expectSec, tag: "exact"|"fuzzy"}, ...]
 *   chunks.json:    [{startTime, endTime, segmentSummary, keywords, embedding, rawSegments}, ...]
 */
import fs from 'node:fs';

const [,, questionsFile, chunksFile, apiKey] = process.argv;
if (!questionsFile || !chunksFile) { console.error('usage: node retrieval_eval.mjs questions.json chunks.json [apiKey]'); process.exit(1); }

const questions = JSON.parse(fs.readFileSync(questionsFile, 'utf8'));
const chunks = JSON.parse(fs.readFileSync(chunksFile, 'utf8'));

// === 复刻项目 normalize / keywordScore / cosine ===
const normalize = (v) => (v || '').toLowerCase().replace(/[\p{P}\p{S}\s]+/gu, '');

function keywordScore(query, chunk) {
  const nq = normalize(query);
  const kws = chunk.keywords || [];
  if (!kws.length) return 0;
  const matched = kws.filter(k => k.trim() && nq.includes(normalize(k))).length;
  return matched / kws.length;
}

function cosine(a, b) {
  if (!a?.length || !b?.length || a.length !== b.length) return 0;
  let dot = 0, na = 0, nb = 0;
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
  return (na && nb) ? dot / (Math.sqrt(na) * Math.sqrt(nb)) : 0;
}

// === query embedding（与项目同模型 bge-m3）===
async function embed(text) {
  const res = await fetch((process.env.SILICONFLOW_BASE_URL || 'https://api.siliconflow.cn/v1') + '/embeddings', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${apiKey || process.env.SILICONFLOW_API_KEY}` },
    body: JSON.stringify({ model: process.env.EMBEDDING_MODEL || 'BAAI/bge-m3', input: text }),
  });
  if (!res.ok) throw new Error('embed failed ' + res.status + ': ' + await res.text());
  const j = await res.json();
  return j.data[0].embedding;
}

// === 期望 chunk：覆盖 expectSec 的 chunk（与 chunk 粒度 5min 一致）===
const expectChunk = (sec) => chunks.find(c => sec * 1000 >= c.startTime && sec * 1000 < c.endTime);

const rank = (mode, scores) => chunks.map((c, i) => ({ c, s: scores[i] })).sort((x, y) => y.s - x.s).slice(0, 3);

async function main() {
  const results = { hybrid: [], vector: [], keyword: [] };
  for (const item of questions) {
    const target = expectChunk(item.expectSec);
    if (!target) { console.error('no chunk covers ' + item.expectSec + 's for q: ' + item.q); continue; }
    const qe = await embed(item.q);
    const vScores = chunks.map(c => cosine(qe, c.embedding));
    const kScores = chunks.map(c => keywordScore(item.q, c));
    const hScores = chunks.map((c, i) => vScores[i] * 0.7 + kScores[i] * 0.3);
    for (const [mode, scores] of [['hybrid', hScores], ['vector', vScores], ['keyword', kScores]]) {
      const top3 = rank(mode, scores);
      const pos = top3.findIndex(t => t.c === target);
      results[mode].push({ q: item.q, tag: item.tag, hit: pos >= 0, rr: pos >= 0 ? 1 / (pos + 1) : 0 });
    }
  }
  const summary = {};
  for (const mode of Object.keys(results)) {
    const rs = results[mode];
    const byTag = (tag) => rs.filter(r => r.tag === tag);
    summary[mode] = {
      n: rs.length,
      hitAt3: +(rs.filter(r => r.hit).length / rs.length).toFixed(3),
      mrrAt3: +(rs.reduce((a, r) => a + r.rr, 0) / rs.length).toFixed(3),
      exactHit: +(byTag('exact').filter(r => r.hit).length / byTag('exact').length).toFixed(3),
      fuzzyHit: +(byTag('fuzzy').filter(r => r.hit).length / byTag('fuzzy').length).toFixed(3),
    };
  }
  fs.writeFileSync('retrieval_results.json', JSON.stringify({ summary, detail: results }, null, 2));
  console.log(JSON.stringify(summary, null, 2));
}
main().catch(e => { console.error(e); process.exit(1); });
