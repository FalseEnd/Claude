'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

/** APP_SECRET 沒設定時自動產生並存檔，重開機後登入狀態不會失效 */
function loadSecret() {
  if (process.env.APP_SECRET) return process.env.APP_SECRET;
  const file = process.env.SECRET_FILE || path.join(__dirname, '..', '.app-secret');
  try { return fs.readFileSync(file, 'utf8').trim(); } catch (_) { /* 第一次啟動 */ }
  const secret = crypto.randomBytes(32).toString('base64url');
  fs.writeFileSync(file, secret, { mode: 0o600 });
  return secret;
}

module.exports = function loadConfig() {
  return {
    port: Number(process.env.PORT || 8080),
    databaseUrl: process.env.DATABASE_URL || 'postgres://xlk:xlk@localhost:5432/xiaoliangkou',
    secret: loadSecret(),
    maxUsers: Number(process.env.MAX_USERS || 2),
    timezone: process.env.TZ || 'Asia/Taipei'
  };
};
