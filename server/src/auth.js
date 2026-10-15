'use strict';
// 密碼雜湊（scrypt）與登入權杖（HMAC-SHA256 簽章）
const crypto = require('crypto');
const { promisify } = require('util');

const scrypt = promisify(crypto.scrypt);
const KEYLEN = 32;
const PARAMS = { N: 16384, r: 8, p: 1 };

async function hashPassword(password) {
  const salt = crypto.randomBytes(16);
  const hash = await scrypt(password, salt, KEYLEN, PARAMS);
  return `scrypt$${salt.toString('base64')}$${hash.toString('base64')}`;
}

async function verifyPassword(password, stored) {
  const [alg, salt, expected] = String(stored || '').split('$');
  if (alg !== 'scrypt' || !salt || !expected) return false;
  const hash = await scrypt(password, Buffer.from(salt, 'base64'), KEYLEN, PARAMS);
  const exp = Buffer.from(expected, 'base64');
  return exp.length === hash.length && crypto.timingSafeEqual(exp, hash);
}

function sign(secret, body) {
  return crypto.createHmac('sha256', secret).update(body).digest('base64url');
}

function signToken(secret, userId) {
  const body = Buffer.from(JSON.stringify({ sub: userId, iat: Math.floor(Date.now() / 1000) })).toString('base64url');
  return `${body}.${sign(secret, body)}`;
}

/** 驗證權杖，成功回傳 { sub, iat }，失敗回傳 null */
function verifyToken(secret, token) {
  const [body, sig] = String(token || '').split('.');
  if (!body || !sig) return null;
  const expected = sign(secret, body);
  if (sig.length !== expected.length || !crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected))) return null;
  try {
    const payload = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'));
    return /^[0-9a-f-]{36}$/.test(payload.sub) && Number.isInteger(payload.iat) ? payload : null;
  } catch (_) {
    return null;
  }
}

module.exports = { hashPassword, verifyPassword, signToken, verifyToken };
