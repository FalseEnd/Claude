'use strict';
// 極簡的路由、JSON 處理與錯誤轉換（不依賴框架）

class HttpError extends Error {
  constructor(status, code, message) {
    super(message || code);
    this.status = status;
    this.code = code;
  }
}
const bad = (code, message) => new HttpError(400, code, message);
const notFound = (message = '找不到資料') => new HttpError(404, 'not_found', message);

// 資料庫丟出的錯誤代碼 → 給 App 看的訊息
const DB_MESSAGES = {
  not_authenticated: '請重新登入',
  already_paired: '已經和別人配對了，請先解除配對',
  not_paired: '還沒有配對',
  invite_invalid: '配對碼無效或已過期，請對方重新產生',
  cannot_pair_with_self: '這是你自己的配對碼，請輸入對方的',
  invalid_duration: '暫停時間要在 24 小時內',
  expense_not_guessable: '這筆開銷不能猜',
  invalid_guess: '請輸入金額',
  rate_limited: '傳太快了，休息一下再傳'
};

function fromDbError(e) {
  if (e instanceof HttpError) return e;
  switch (e.code) {
    case 'P0001': return new HttpError(400, e.message, DB_MESSAGES[e.message] || e.message);
    case '42501': return new HttpError(403, 'forbidden', '沒有權限（對方可能沒有分享這項資料）');
    case '23505': return new HttpError(409, 'conflict', '資料重複了');
    case '23503': return new HttpError(400, 'invalid_reference', '關聯的資料不存在');
    case '23502': case '23514': case '22P02': case '22007': case '22008': case '22003': case '22023': case '2201B':
      return new HttpError(400, 'invalid_input', '輸入的資料格式不正確');
    default: return null;
  }
}

class Router {
  constructor() { this.routes = []; }
  add(method, pattern, handler, opts = {}) {
    const keys = [];
    const re = new RegExp('^' + pattern.replace(/:(\w+)/g, (_, k) => { keys.push(k); return '([^/]+)'; }) + '/?$');
    this.routes.push({ method, re, keys, handler, auth: opts.auth !== false });
  }
  match(method, pathname) {
    let pathMatched = false;
    for (const r of this.routes) {
      const m = pathname.match(r.re);
      if (!m) continue;
      pathMatched = true;
      if (r.method !== method) continue;
      const params = {};
      r.keys.forEach((k, i) => { params[k] = decodeURIComponent(m[i + 1]); });
      return { route: r, params };
    }
    return pathMatched ? { methodNotAllowed: true } : null;
  }
}

function readJson(req, limit = 1 << 20) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on('data', (c) => {
      size += c.length;
      if (size > limit) { reject(bad('too_large', '資料太大')); req.destroy(); return; }
      chunks.push(c);
    });
    req.on('end', () => {
      if (!chunks.length) return resolve({});
      try { resolve(JSON.parse(Buffer.concat(chunks).toString('utf8'))); } catch (_) { reject(bad('invalid_json', 'JSON 格式錯誤')); }
    });
    req.on('error', reject);
  });
}

function send(res, status, data, type = 'application/json; charset=utf-8') {
  const body = type.startsWith('application/json') ? JSON.stringify(data) : data;
  res.writeHead(status, { 'content-type': type, 'cache-control': 'no-store' });
  res.end(body);
}

const toCamel = (k) => k.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
/** snake_case 欄位 → camelCase（遞迴處理陣列） */
function camel(v) {
  if (Array.isArray(v)) return v.map(camel);
  if (v === null || typeof v !== 'object' || v instanceof Date) return v;
  const out = {};
  for (const [k, val] of Object.entries(v)) out[toCamel(k)] = val;
  return out;
}

/**
 * 依 body 中有提供的欄位組 UPDATE 的 SET 子句。
 * map: { camelKey: 'column' } 或 { camelKey: ['column', 'cast'] }
 */
function buildSet(body, map, startIndex = 1) {
  const sets = [];
  const values = [];
  for (const [key, spec] of Object.entries(map)) {
    if (!(key in body)) continue;
    const [col, cast] = Array.isArray(spec) ? spec : [spec, null];
    values.push(body[key]);
    sets.push(`${col} = $${startIndex + values.length - 1}${cast ? '::' + cast : ''}`);
  }
  return { sql: sets.join(', '), values };
}

module.exports = { HttpError, bad, notFound, fromDbError, Router, readJson, send, camel, buildSet };
