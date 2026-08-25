#!/usr/bin/env node
/** DoVideoAI 测试驱动：登录/上传/分析/轮询/取trace。用法见 usage() */
const BASE = process.env.DOV_BASE || 'http://localhost:9090';
const USER = process.env.DOV_USER || 'testbot';
const PASS = process.env.DOV_PASS || 'Test12345678';

let TOKEN = '';

async function api(path, opts = {}) {
  const res = await fetch(BASE + path, {
    ...opts,
    headers: { Authorization: TOKEN ? `Bearer ${TOKEN}` : '', ...(opts.headers || {}) },
  });
  const text = await res.text();
  let body;
  try { body = JSON.parse(text); } catch { body = text; }
  return { status: res.status, body };
}

async function ensureLogin() {
  await api('/user/register', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  }).catch(() => {});
  const r = await api('/user/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: USER, password: PASS }),
  });
  if (r.status !== 200 || !r.body?.token) {
    // 兼容裸 token / Bearer 两种解析
    throw new Error('login failed: ' + JSON.stringify(r.body).slice(0, 200));
  }
  TOKEN = r.body.token;
  return r.body;
}

async function cmdLogin() { const b = await ensureLogin(); console.log(JSON.stringify(b.userInfo)); }

async function upload(file) {
  await ensureLogin();
  const fs = await import('node:fs');
  const buf = fs.readFileSync(file);
  const form = new FormData();
  const name = file.split(/[\\/]/).pop();
  form.append('file', new Blob([buf]), name);
  const r = await api('/media/upload', { method: 'POST', body: form });
  if (r.status !== 200) throw new Error('upload failed: ' + JSON.stringify(r.body));
  // 按 filename 找 mediaId
  const list = await api('/media/list');
  const item = (Array.isArray(list.body) ? list.body : []).find(m => m.filename === name);
  if (!item) throw new Error('media not found after upload: ' + name);
  console.log(JSON.stringify({ mediaId: item.id, filename: name }));
}

async function analyze(id, goal, timeoutMs = 7200000) {
  await ensureLogin();
  const t0 = Date.now();
  const r = await api(`/analysis/ai?id=${id}&goal=${encodeURIComponent(goal)}`, { method: 'POST' });
  if (r.status >= 300) throw new Error('analyze submit failed: ' + JSON.stringify(r.body));
  // 轮询
  for (;;) {
    await new Promise(res => setTimeout(res, 3000));
    const s = await api(`/analysis/analysis-status?id=${id}&goal=${encodeURIComponent(goal)}`);
    const state = s.body?.state;
    if (state === 'COMPLETED') {
      const trace = await api(`/analysis/agent-trace?id=${id}&goal=${encodeURIComponent(goal)}`);
      console.log(JSON.stringify({ mediaId: id, state, elapsedMs: Date.now() - t0, trace: trace.body }));
      return;
    }
    if (state === 'FAILED') {
      const trace = await api(`/analysis/agent-trace?id=${id}&goal=${encodeURIComponent(goal)}`);
      console.log(JSON.stringify({ mediaId: id, state, message: s.body?.message, elapsedMs: Date.now() - t0, trace: trace.body }));
      process.exitCode = 2;
      return;
    }
    if (Date.now() - t0 > timeoutMs) { console.error('TIMEOUT'); process.exitCode = 3; return; }
  }
}

async function trace(id, goal) {
  await ensureLogin();
  const r = await api(`/analysis/agent-trace?id=${id}&goal=${encodeURIComponent(goal)}`);
  console.log(JSON.stringify(r.body));
}

async function status(id, goal) {
  await ensureLogin();
  const r = await api(`/analysis/analysis-status?id=${id}&goal=${encodeURIComponent(goal)}`);
  console.log(JSON.stringify({ state: r.body?.state, message: r.body?.message, hasResult: !!r.body?.result }));
}

async function summary(id) {
  await ensureLogin();
  const s = await api(`/analysis/analysis-status?id=${id}&goal=${encodeURIComponent(process.env.DOV_GOAL || '理解视频核心内容并生成结构化分析报告')}`);
  console.log(s.body?.result || '(no result)');
}

const [cmd, ...args] = process.argv.slice(2);
const usage = () => console.error('usage: node test_driver.mjs login|upload <file>|analyze <mediaId> <goal>|trace <mediaId> <goal>|status <mediaId> <goal>|summary <mediaId>');
switch (cmd) {
  case 'login': await cmdLogin(); break;
  case 'upload': await upload(args[0]); break;
  case 'analyze': await analyze(Number(args[0]), args.slice(1).join(' ') || '理解视频核心内容并生成结构化分析报告'); break;
  case 'trace': await trace(Number(args[0]), args.slice(1).join(' ')); break;
  case 'status': await status(Number(args[0]), args.slice(1).join(' ')); break;
  case 'summary': await summary(Number(args[0])); break;
  default: usage(); process.exit(1);
}
