'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

/** 解析 .env 文字：KEY=value，忽略空行與 # 註解，值可以加引號 */
function parseEnv(text) {
  const out = {};
  for (const line of String(text || '').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m) out[m[1]] = m[2].replace(/^(['"])(.*)\1$/, '$2');
  }
  return out;
}

/** 讀取 server/.env（不用 Docker 的啟動程式會產生這個檔案）；已存在的環境變數優先 */
function loadEnvFile(file = process.env.XLK_ENV_FILE || path.join(__dirname, '..', '.env')) {
  let text;
  try { text = fs.readFileSync(file, 'utf8'); } catch (_) { return; }
  for (const [k, v] of Object.entries(parseEnv(text))) if (!(k in process.env)) process.env[k] = v;
}

/** APP_SECRET 沒設定時自動產生並存檔，重開機後登入狀態不會失效 */
function loadSecret() {
  if (process.env.APP_SECRET) return process.env.APP_SECRET;
  const file = process.env.SECRET_FILE || path.join(__dirname, '..', '.app-secret');
  try { return fs.readFileSync(file, 'utf8').trim(); } catch (_) { /* 第一次啟動 */ }
  const secret = crypto.randomBytes(32).toString('base64url');
  fs.writeFileSync(file, secret, { mode: 0o600 });
  return secret;
}

function loadConfig() {
  loadEnvFile();
  return {
    port: Number(process.env.PORT || 8080),
    databaseUrl: process.env.DATABASE_URL || 'postgres://xlk:xlk@localhost:5432/xiaoliangkou',
    secret: loadSecret(),
    maxUsers: Number(process.env.MAX_USERS || 2),
    timezone: process.env.TZ || 'Asia/Taipei'
  };
}

module.exports = loadConfig;
module.exports.loadEnvFile = loadEnvFile;
module.exports.parseEnv = parseEnv;
