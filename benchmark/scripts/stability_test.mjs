#!/usr/bin/env node
/** 测试5：连续 10 个分析任务，统计成功率与耗时分布（顺序执行） */
const BASE = process.env.DOV_BASE || 'http://localhost:9090';
const TASKS = [
  { id: 2, goal: '二分查找的边界处理要点' },
  { id: 2, goal: '红蓝染色法怎么使用' },
  { id: 7, goal: '开区间写法总结' },
  { id: 7, goal: '这道题的时间复杂度分析' },
  { id: 3, goal: '全排列的回溯模板' },
  { id: 3, goal: 'N皇后问题的约束条件' },
  { id: 3, goal: '排列去重怎么处理' },
  { id: 3, goal: '回溯剪枝技巧总结' },
  { id: 4, goal: 'transformer 学习建议汇总' },
  { id: 4, goal: '多模态方向怎么入门' },
];

let TOKEN = '';
async function api(path, opts = {}) {
  const res = await fetch(BASE + path, { ...opts, headers: { Authorization: 'Bearer ' + TOKEN, ...(opts.headers || {}) } });
  const text = await res.text();
  try { return { status: res.status, body: JSON.parse(text) }; } catch { return { status: res.status, body: text }; }
}
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

async function main() {
  const login = await api('/user/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ username: 'testbot', password: 'Test12345678' }) });
  TOKEN = login.body.token;
  const results = [];
  for (const [i, t] of TASKS.entries()) {
    const t0 = Date.now();
    const submit = await api(`/analysis/ai?id=${t.id}&goal=${encodeURIComponent(t.goal)}`, { method: 'POST' });
    if (submit.status >= 300) { results.push({ i, ...t, ok: false, ms: 0, note: 'submit ' + submit.status }); console.log(`#${i} submit-fail`); continue; }
    let state = 'PROCESSING', note = '';
    for (;;) {
      await sleep(5000);
      const s = await api(`/analysis/analysis-status?id=${t.id}&goal=${encodeURIComponent(t.goal)}`);
      state = s.body?.state; note = s.body?.message || '';
      if (state === 'COMPLETED' || state === 'FAILED' || Date.now() - t0 > 600000) break;
    }
    const ms = Date.now() - t0;
    results.push({ i, mediaId: t.id, goal: t.goal, ok: state === 'COMPLETED', state, ms, note });
    console.log(`#${i} media=${t.id} ${state} ${Math.round(ms / 1000)}s`);
  }
  const ok = results.filter(r => r.ok);
  const times = ok.map(r => r.ms).sort((a, b) => a - b);
  const summary = {
    total: results.length, success: ok.length, successRate: +(ok.length / results.length).toFixed(3),
    avgMs: Math.round(times.reduce((a, b) => a + b, 0) / (times.length || 1)),
    minMs: times[0], p50Ms: times[Math.floor(times.length / 2)], maxMs: times[times.length - 1],
  };
  require('fs').writeFileSync('stability_results.json', JSON.stringify({ summary, results }, null, 2));
  console.log('SUMMARY', JSON.stringify(summary));
}
main().catch(e => { console.error(e); process.exit(1); });
