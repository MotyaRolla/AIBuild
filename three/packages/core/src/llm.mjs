/**
 * DeepSeek V4.1 Flash client (via Command Code) for the 3D pipeline.
 *
 * Same gateway and transports as the Minecraft plugin (packages/../plugin/src/main/java/dev/aibuild/ai),
 * but here we need something the plugin cannot do: send IMAGES to the model, because the whole idea is
 * that the AI looks at rendered views of its own build and criticises them.
 *
 * Transport: OpenAI-compatible Chat Completions at https://api.commandcode.ai/provider/v1/chat/completions
 *   - text-only requests: plain content string
 *   - vision requests: content parts [{type:'text'},{type:'image_url',image_url:{url:'data:image/png;base64,...'}}]
 * The key is read from COMMANDCODE_API_KEY or from the file given in `keyFile`.
 */
import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';

const DEFAULT_BASE = 'https://api.commandcode.ai/provider/v1';
const DEFAULT_MODEL = 'deepseek/deepseek-v4.1-flash';

export class LlmError extends Error {
  constructor(message, { status = 0, code = null, retryable = false, body = null } = {}) {
    super(message);
    this.name = 'LlmError';
    this.status = status;
    this.code = code;
    this.retryable = retryable;
    this.body = body;
  }
}

/** Reads the API key from env or the usual local file (never logged). */
export async function loadApiKey({ env = process.env, keyFile = '/root/ai-build/.cc_key' } = {}) {
  if (env.COMMANDCODE_API_KEY) return env.COMMANDCODE_API_KEY.trim();
  if (env.COMMAND_CODE_API_KEY) return env.COMMAND_CODE_API_KEY.trim();
  for (const f of [keyFile, '.cc_key']) {
    if (existsSync(f)) {
      const k = (await readFile(f, 'utf8')).trim();
      if (k) return k;
    }
  }
  return '';
}

/**
 * Creates the llm function consumed by packages/core/src/loop.mjs:
 *   llm({system, user, json, images}) -> {content, usage}
 *
 * @param {object} [opts]
 * @param {string} [opts.apiKey]      key; defaults to loadApiKey()
 * @param {string} [opts.model]       model id
 * @param {string} [opts.baseUrl]
 * @param {string|null} [opts.effort] reasoning effort: off|low|medium|high|xhigh|max
 * @param {number} [opts.maxTokens]
 * @param {number} [opts.temperature]
 * @param {number} [opts.maxRetries]
 * @param {(msg: string) => void} [opts.log]
 */
export async function createLlm(opts = {}) {
  const apiKey = opts.apiKey ?? (await loadApiKey());
  if (!apiKey) throw new Error('no Command Code API key: set COMMANDCODE_API_KEY or pass apiKey');
  const baseUrl = (opts.baseUrl || DEFAULT_BASE).replace(/\/$/, '');
  const model = opts.model || DEFAULT_MODEL;
  const effort = opts.effort === undefined ? 'low' : opts.effort;
  const maxTokens = opts.maxTokens || 32000;
  const temperature = opts.temperature ?? 0.6;
  const maxRetries = opts.maxRetries ?? 3;
  const log = opts.log || (() => {});
  const stats = { calls: 0, promptTokens: 0, completionTokens: 0, reasoningTokens: 0, ms: 0 };

  async function callOnce({ system, user, json, images, effortOverride }) {
    const content = [];
    if (user) content.push({ type: 'text', text: user });
    for (const img of images || []) {
      content.push({ type: 'image_url', image_url: { url: toDataUrl(img) } });
    }
    const messages = [];
    if (system) messages.push({ role: 'system', content: system });
    messages.push({ role: 'user', content: content.length === 1 && content[0].type === 'text' ? content[0].text : content });
    const body = {
      model,
      messages,
      max_tokens: maxTokens,
      temperature,
      stream: false,
    };
    if (json) body.response_format = { type: 'json_object' };
    if (effortOverride !== null) body.reasoning_effort = effortOverride;

    const t0 = Date.now();
    const res = await fetch(`${baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${apiKey}`,
        'Content-Type': 'application/json',
        'User-Agent': 'AIBuild3D/0.1',
      },
      body: JSON.stringify(body),
    });
    const text = await res.text();
    stats.ms += Date.now() - t0;
    if (!res.ok) {
      let code = null;
      let message = text.slice(0, 500);
      try {
        const j = JSON.parse(text);
        code = j?.error?.code || null;
        message = j?.error?.message || message;
      } catch {
        /* keep raw text */
      }
      throw new LlmError(`HTTP ${res.status}: ${message}`, {
        status: res.status,
        code,
        retryable: res.status === 429 || res.status >= 500 || res.status === 408,
        body: text.slice(0, 2000),
      });
    }
    const j = JSON.parse(text);
    const choice = j.choices?.[0];
    const out = choice?.message?.content ?? '';
    const usage = j.usage || {};
    stats.calls++;
    stats.promptTokens += usage.prompt_tokens || 0;
    stats.completionTokens += usage.completion_tokens || 0;
    stats.reasoningTokens += usage.completion_tokens_details?.reasoning_tokens || 0;
    if (!out || !out.trim()) {
      throw new LlmError('empty answer from the model', { status: 0, code: 'empty_content', retryable: true });
    }
    return { content: out, reasoning: choice?.message?.reasoning_content || '', usage, finish: choice?.finish_reason };
  }

  /**
   * @param {{system?: string, user?: string, json?: boolean, images?: (Buffer|string)[]}} req
   * @returns {Promise<{content: string, usage: object, reasoning: string}>}
   */
  async function llm(req) {
    let effortNow = effort;
    let lastErr = null;
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
      try {
        return await callOnce({ ...req, effortOverride: effortNow });
      } catch (e) {
        lastErr = e;
        if (!(e instanceof LlmError) || !e.retryable) throw e;
        // a truncated/empty answer with heavy thinking: retry with less reasoning
        if (e.code === 'empty_content' && effortNow && attempt >= 1) {
          effortNow = lowerEffort(effortNow);
          log(`empty answer, retrying with reasoning_effort=${effortNow}`);
        }
        const wait = [2000, 5000, 12000][Math.min(attempt, 2)];
        log(`LLM retry ${attempt + 1}/${maxRetries} in ${wait} ms (${e.message})`);
        await new Promise((r) => setTimeout(r, wait));
      }
    }
    throw lastErr;
  }

  llm.stats = stats;
  llm.model = model;
  return llm;
}

function toDataUrl(img) {
  if (typeof img === 'string') {
    if (img.startsWith('data:')) return img;
    return `data:image/png;base64,${Buffer.from(img, 'base64').toString('base64')}`;
  }
  if (Buffer.isBuffer(img)) return `data:image/png;base64,${img.toString('base64')}`;
  throw new Error('image must be a Buffer or a data URL string');
}

export function lowerEffort(e) {
  return { max: 'xhigh', xhigh: 'high', high: 'medium', medium: 'low', low: 'off', off: 'off' }[e] || 'off';
}

/** Convenience: ask for JSON and parse it tolerantly (fences, prose, trailing commas). */
export async function askJson(llm, req) {
  const res = await llm({ ...req, json: true });
  return { ...parseJsonLoose(res.content), usage: res.usage, raw: res.content };
}

/** Extracts the first JSON object/array from free text and repairs common LLM damage. */
export function parseJsonLoose(text) {
  if (typeof text !== 'string' || !text.trim()) throw new Error('empty JSON answer');
  let s = text.trim();
  const fence = s.indexOf('```');
  if (fence >= 0) {
    const nl = s.indexOf('\n', fence);
    const end = s.indexOf('```', nl < 0 ? fence + 3 : nl);
    if (nl >= 0) s = end > nl ? s.slice(nl + 1, end) : s.slice(nl + 1);
  }
  const start = Math.min(...[s.indexOf('{'), s.indexOf('[')].filter((i) => i >= 0));
  if (!Number.isFinite(start)) throw new Error('no JSON in the answer');
  s = s.slice(start);
  // A complete JSON value followed by prose: parse just the leading value.
  try {
    const { value, end } = parseLeading(s);
    if (end > 0 && end < s.length) return { value, repaired: 'trailing prose dropped' };
    return { value };
  } catch {
    /* fall through */
  }
  const attempt = (t) => JSON.parse(t);
  const noTrailing = s.replace(/,\s*([}\]])/g, '$1');
  try {
    return { value: attempt(noTrailing), repaired: 'trailing commas' };
  } catch {
    /* fall through */
  }
  const closed = closeTruncated(noTrailing);
  if (closed) {
    try {
      return { value: attempt(closed), repaired: 'closed truncated JSON' };
    } catch {
      /* fall through */
    }
  }
  throw new Error('invalid JSON in the answer');
}

/**
 * Parses one JSON value starting at index 0 and reports where it ended.
 * Used to drop prose that the model wrote after a complete JSON answer.
 */
function parseLeading(s) {
  let depth = 0;
  let inStr = false;
  let esc = false;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === '{' || c === '[') depth++;
    else if (c === '}' || c === ']') {
      depth--;
      if (depth === 0) {
        const text = s.slice(0, i + 1);
        return { value: JSON.parse(text), end: i + 1 };
      }
    }
  }
  throw new Error('unbalanced JSON');
}

/** Closes JSON that was cut off mid-stream at the last complete element. */
function closeTruncated(s) {
  const stack = [];
  let inStr = false;
  let esc = false;
  let lastSafe = -1;
  let stackAtSafe = null;
  for (let i = 0; i < s.length; i++) {
    const c = s[i];
    if (inStr) {
      if (esc) esc = false;
      else if (c === '\\') esc = true;
      else if (c === '"') inStr = false;
      continue;
    }
    if (c === '"') inStr = true;
    else if (c === '{' || c === '[') stack.push(c);
    else if (c === '}' || c === ']') {
      stack.pop();
      if (stack.length === 0) return s.slice(0, i + 1);
      lastSafe = i + 1;
      stackAtSafe = [...stack];
    } else if (c === ',') {
      lastSafe = i;
      stackAtSafe = [...stack];
    }
  }
  if (lastSafe < 0 || !stackAtSafe) return null;
  let out = s.slice(0, lastSafe).trim();
  while (out.endsWith(':') || out.endsWith(',')) out = out.slice(0, -1);
  for (const open of stackAtSafe.reverse()) out += open === '{' ? '}' : ']';
  return out;
}
