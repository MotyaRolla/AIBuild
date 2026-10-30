#!/usr/bin/env node
// Minimal Pterodactyl client-API helper for the "ai-build" server only.
// Usage:
//   node tools/panel.mjs status
//   node tools/panel.mjs power <start|stop|restart|kill>
//   node tools/panel.mjs cmd "<console command>"
//   node tools/panel.mjs logs [seconds=10] [--grep REGEX]     (stream console via websocket)
//   node tools/panel.mjs run "<command>" [seconds=8] [--grep REGEX] (send command, capture console output)
//   node tools/panel.mjs wait-ready [timeoutSec=300]            (wait until "Done (" appears / state running)
//   node tools/panel.mjs ls <dir>
//   node tools/panel.mjs cat <file>
//   node tools/panel.mjs write <remoteFile> <localFile>
//   node tools/panel.mjs upload <remoteDir> <localFile...>
//   node tools/panel.mjs download <remoteFile> <localFile>
//   node tools/panel.mjs mkdir <parentDir> <name>
//   node tools/panel.mjs rm <dir> <name...>
import fs from 'node:fs';
import path from 'node:path';

const ROOT = path.dirname(path.dirname(new URL(import.meta.url).pathname));
// Credentials and the panel URL are NOT stored in the repository: keep them in files/env outside git
// (see .gitignore): PTERO_KEY / PANEL_URL / PTERO_SERVER env vars or .ptero_key / .ptero_url / .ptero_server files.
const KEY = process.env.PTERO_KEY || readIfExists(process.env.PTERO_KEY_FILE || path.join(ROOT, '.ptero_key'));
const PANEL = (process.env.PANEL_URL || readIfExists(path.join(ROOT, '.ptero_url')) || 'https://panel.example.host').replace(/\/$/, '');
const SERVER = process.env.PTERO_SERVER || readIfExists(path.join(ROOT, '.ptero_server'));
const API = `${PANEL}/api/client/servers/${SERVER}`;

function readIfExists(file) {
  try { return fs.readFileSync(file, 'utf8').trim(); } catch { return ''; }
}
const H = { Authorization: `Bearer ${KEY}`, Accept: 'application/json' };

async function api(method, p, body, extraHeaders = {}) {
  const res = await fetch(API + p, { method, headers: { ...H, ...extraHeaders }, body });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${p} -> HTTP ${res.status}: ${text.slice(0, 500)}`);
  try { return text ? JSON.parse(text) : {}; } catch { return text; }
}
const enc = encodeURIComponent;
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const stripAnsi = (s) => s.replace(/\u001b\[[0-9;]*[A-Za-z]/g, '');

async function openConsole(onLine) {
  const { data } = await api('GET', '/websocket');
  const ws = new WebSocket(data.socket, { headers: { Origin: PANEL } });
  await new Promise((resolve, reject) => {
    ws.onopen = () => { ws.send(JSON.stringify({ event: 'auth', args: [data.token] })); };
    ws.onerror = (e) => reject(new Error('websocket error ' + (e.message || '')));
    ws.onmessage = (m) => {
      const msg = JSON.parse(m.data);
      if (msg.event === 'auth success') { resolve(); }
      else if (msg.event === 'console output') { onLine(stripAnsi(msg.args[0])); }
      else if (msg.event === 'status') { onLine(`[status] ${msg.args[0]}`); }
      else if (msg.event === 'token expiring') {
        api('GET', '/websocket').then(({ data: d }) => ws.send(JSON.stringify({ event: 'auth', args: [d.token] }))).catch(() => {});
      } else if (msg.event === 'jwt error') { reject(new Error('jwt error ' + msg.args)); }
    };
  });
  return ws;
}

function lineFilter(argv) {
  const gi = argv.indexOf('--grep');
  if (gi >= 0) { const re = new RegExp(argv[gi + 1], 'i'); argv.splice(gi, 2); return (l) => re.test(l); }
  return () => true;
}

const [, , cmd, ...args] = process.argv;
switch (cmd) {
  case 'status': {
    const r = await api('GET', '/resources');
    const a = r.attributes; console.log(a.current_state, JSON.stringify(a.resources));
    break;
  }
  case 'power': await api('POST', '/power', JSON.stringify({ signal: args[0] }), { 'Content-Type': 'application/json' }); console.log('ok'); break;
  case 'cmd': await api('POST', '/command', JSON.stringify({ command: args.join(' ') }), { 'Content-Type': 'application/json' }); console.log('sent'); break;
  case 'logs': {
    const f = lineFilter(args); const secs = Number(args[0] || 10);
    const ws = await openConsole((l) => { if (f(l)) console.log(l); });
    ws.send(JSON.stringify({ event: 'send logs', args: [null] }));
    await sleep(secs * 1000); ws.close(); break;
  }
  case 'run': {
    const f = lineFilter(args); const command = args[0]; const secs = Number(args[1] || 8);
    let started = false;
    const ws = await openConsole((l) => { if (started && f(l)) console.log(l); });
    await sleep(300); started = true;
    ws.send(JSON.stringify({ event: 'send command', args: [command] }));
    await sleep(secs * 1000); ws.close(); break;
  }
  case 'wait-ready': {
    const timeout = Number(args[0] || 300) * 1000; const t0 = Date.now();
    let done = false;
    const ws = await openConsole((l) => {
      if (/Done \(|\[status\] running/.test(l)) { if (/Done \(/.test(l)) done = true; }
      if (/ERROR|Exception|WARN.*AIBuild|AIBuild/i.test(l)) console.log(l);
    });
    ws.send(JSON.stringify({ event: 'send logs', args: [null] }));
    while (!done && Date.now() - t0 < timeout) await sleep(1000);
    ws.close(); console.log(done ? 'READY' : 'TIMEOUT'); break;
  }
  case 'ls': {
    const r = await api('GET', `/files/list?directory=${enc(args[0] || '/')}`);
    for (const f of r.data) { const a = f.attributes; console.log(`${a.is_file ? 'F' : 'D'} ${a.name}\t${a.size}\t${a.modified_at}`); }
    break;
  }
  case 'tail': { // tail [n=30] [--grep REGEX] — read logs/latest.log via the file API (no websocket rate limits)
    const f = lineFilter(args); const n = Number(args[0] || 30);
    const res = await fetch(`${API}/files/contents?file=${enc('/logs/latest.log')}`, { headers: H });
    const lines = (await res.text()).split('\n').filter(f);
    console.log(lines.slice(-n).join('\n')); break;
  }
  case 'cat': { const res = await fetch(`${API}/files/contents?file=${enc(args[0])}`, { headers: H }); console.log(await res.text()); break; }
  case 'write': {
    const res = await fetch(`${API}/files/write?file=${enc(args[0])}`, { method: 'POST', headers: { ...H, 'Content-Type': 'text/plain' }, body: fs.readFileSync(args[1]) });
    console.log(res.status); break;
  }
  case 'upload': {
    const dir = args[0];
    const { attributes } = await api('GET', '/files/upload');
    const fd = new FormData();
    for (const lf of args.slice(1)) fd.append('files', new Blob([fs.readFileSync(lf)]), path.basename(lf));
    const res = await fetch(`${attributes.url}&directory=${enc(dir)}`, { method: 'POST', body: fd });
    console.log(res.status, await res.text()); break;
  }
  case 'download': {
    const r = await api('GET', `/files/download?file=${enc(args[0])}`);
    const res = await fetch(r.attributes.url); fs.writeFileSync(args[1], Buffer.from(await res.arrayBuffer()));
    console.log('saved', args[1]); break;
  }
  case 'mkdir': await api('POST', '/files/create-folder', JSON.stringify({ root: args[0], name: args[1] }), { 'Content-Type': 'application/json' }); console.log('ok'); break;
  case 'rm': await api('POST', '/files/delete', JSON.stringify({ root: args[0], files: args.slice(1) }), { 'Content-Type': 'application/json' }); console.log('ok'); break;
  default: console.log('unknown command'); process.exit(2);
}
