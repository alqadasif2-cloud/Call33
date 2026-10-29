'use strict';

const crypto = require('node:crypto');
const { promisify } = require('node:util');

const scryptAsync = promisify(crypto.scrypt);

const USERNAME_REGEX = /^[a-zA-Z0-9_.\-\u0600-\u06FF]{3,32}$/;
const SCRYPT_PARAMS = { N: 16384, r: 8, p: 1, maxmem: 32 * 1024 * 1024 };
const SCRYPT_KEYLEN = 64;

const RATE_WINDOW_MS = 15 * 60 * 1000; // 15 minutes
const LOCKOUT_MS = 15 * 60 * 1000; // 15 minutes
const MAX_LOGIN_FAILURES = 5;
const MAX_REGISTER_ATTEMPTS = 8;

class AuthServiceError extends Error {
  constructor(code, messageAr, status = 400) {
    super(messageAr);
    this.code = code;
    this.messageAr = messageAr;
    this.status = status;
  }
}

/**
 * Normalizes and validates a username identically across Cloud Functions, Android, and Web.
 */
function normalizeUsername(rawUsername) {
  if (typeof rawUsername !== 'string') {
    throw new AuthServiceError('invalid-username', 'يرجى إدخال اسم المستخدم.', 400);
  }
  const username = rawUsername.trim();
  if (username.length < 3 || username.length > 32) {
    throw new AuthServiceError(
      'invalid-username',
      'يجب أن يتكون اسم المستخدم من 3 إلى 32 حرفًا أو رقمًا دون مسافات.',
      400
    );
  }
  if (!USERNAME_REGEX.test(username)) {
    throw new AuthServiceError(
      'invalid-username',
      'اسم المستخدم يقبل الحروف والأرقام والنقطة والشرطة فقط دون مسافات.',
      400
    );
  }
  const usernameLower = username.toLowerCase();
  const usernameId = crypto
    .createHash('sha256')
    .update(`uname:${usernameLower}`, 'utf8')
    .digest('hex')
    .slice(0, 40);

  return { username, usernameLower, usernameId };
}

/**
 * Validates password length constraints without leaking content.
 */
function validatePassword(rawPassword) {
  if (typeof rawPassword !== 'string' || rawPassword.length < 8) {
    throw new AuthServiceError(
      'weak-password',
      'يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.',
      400
    );
  }
  if (rawPassword.length > 128) {
    throw new AuthServiceError(
      'invalid-password',
      'كلمة المرور تتجاوز الحد الأقصى المسموح به (128 حرفًا).',
      400
    );
  }
  return rawPassword;
}

/**
 * Hashes a password with a cryptographically random 32-byte salt using scrypt.
 * Plaintext password is never persisted.
 */
async function hashPassword(password) {
  const saltBuf = crypto.randomBytes(32);
  const derived = await scryptAsync(
    Buffer.from(password, 'utf8'),
    saltBuf,
    SCRYPT_KEYLEN,
    SCRYPT_PARAMS
  );
  return {
    alg: 'scrypt-v1',
    passwordSalt: saltBuf.toString('hex'),
    passwordHash: Buffer.from(derived).toString('hex'),
  };
}

/**
 * Verifies a password against a stored scrypt hash using constant-time comparison.
 */
async function verifyPassword(password, passwordSaltHex, passwordHashHex) {
  if (
    typeof password !== 'string' ||
    typeof passwordSaltHex !== 'string' ||
    typeof passwordHashHex !== 'string' ||
    passwordSaltHex.length < 32 ||
    passwordHashHex.length !== SCRYPT_KEYLEN * 2
  ) {
    return false;
  }
  const saltBuf = Buffer.from(passwordSaltHex, 'hex');
  const expectedBuf = Buffer.from(passwordHashHex, 'hex');
  if (expectedBuf.length !== SCRYPT_KEYLEN) return false;

  const actualBuf = Buffer.from(
    await scryptAsync(Buffer.from(password, 'utf8'), saltBuf, SCRYPT_KEYLEN, SCRYPT_PARAMS)
  );
  return crypto.timingSafeEqual(actualBuf, expectedBuf);
}

function makeLimitId(prefix, rawKey) {
  const digest = crypto.createHash('sha256').update(`${prefix}:${rawKey}`, 'utf8').digest('hex');
  return `${prefix}_${digest.slice(0, 32)}`;
}

/**
 * Evaluates rate-limit window state and returns updated state or throws 429 AuthServiceError.
 */
function evaluateRateLimit(existingState, maxAttempts, nowMs) {
  if (existingState && existingState.blockedUntilMs && existingState.blockedUntilMs > nowMs) {
    const minutesLeft = Math.max(1, Math.ceil((existingState.blockedUntilMs - nowMs) / 60000));
    throw new AuthServiceError(
      'too-many-requests',
      `تم إيقاف المحاولات مؤقتًا لكثرة المحاولات الخاطئة. يرجى الانتظار ${minutesLeft} دقيقة ثم المحاولة مجددًا.`,
      429
    );
  }
  const expiredWindow =
    !existingState ||
    !existingState.windowStartMs ||
    nowMs - existingState.windowStartMs > RATE_WINDOW_MS;

  return expiredWindow
    ? { attempts: 0, windowStartMs: nowMs, blockedUntilMs: 0 }
    : {
        attempts: Number(existingState.attempts || 0),
        windowStartMs: Number(existingState.windowStartMs),
        blockedUntilMs: 0,
      };
}

function recordFailedAttempt(currentState, maxAttempts, nowMs) {
  const base = evaluateRateLimit(currentState, maxAttempts, nowMs);
  const attempts = base.attempts + 1;
  const blockedUntilMs = attempts >= maxAttempts ? nowMs + LOCKOUT_MS : 0;
  return {
    attempts,
    windowStartMs: base.windowStartMs,
    blockedUntilMs,
  };
}

/**
 * Executes registration or login against an injected backend store (Firestore Admin in prod, in-memory in unit tests).
 */
async function executeUsernameAuth({
  action,
  username,
  password,
  clientIp = 'unknown',
  nowMs = Date.now(),
  store,
}) {
  const norm = normalizeUsername(username);
  const cleanPassword = validatePassword(password);

  const ipLimitKey = makeLimitId('ip', clientIp);
  const acctLimitKey = makeLimitId('acct', norm.usernameId);

  if (action === 'register') {
    const ipState = await store.getRateLimit(ipLimitKey);
    evaluateRateLimit(ipState, MAX_REGISTER_ATTEMPTS, nowMs);
    await store.setRateLimit(
      ipLimitKey,
      recordFailedAttempt(ipState, MAX_REGISTER_ATTEMPTS, nowMs)
    );

    const hashed = await hashPassword(cleanPassword);
    const uid = await store.createAuthAccount({
      username: norm.username,
      usernameLower: norm.usernameLower,
      usernameId: norm.usernameId,
      ...hashed,
      nowMs,
    });

    const customToken = await store.issueCustomToken(uid, {
      username: norm.username,
      usernameLower: norm.usernameLower,
    });

    return {
      uid,
      username: norm.username,
      usernameLower: norm.usernameLower,
      usernameId: norm.usernameId,
      customToken,
    };
  }

  if (action === 'login') {
    const [ipState, acctState] = await Promise.all([
      store.getRateLimit(ipLimitKey),
      store.getRateLimit(acctLimitKey),
    ]);
    evaluateRateLimit(ipState, MAX_LOGIN_FAILURES * 2, nowMs);
    evaluateRateLimit(acctState, MAX_LOGIN_FAILURES, nowMs);

    const cred = await store.getCredential(norm.usernameId);
    if (!cred) {
      // Perform dummy scrypt calculation to prevent timing-based username enumeration
      await hashPassword(cleanPassword);
      await Promise.all([
        store.setRateLimit(
          ipLimitKey,
          recordFailedAttempt(ipState, MAX_LOGIN_FAILURES * 2, nowMs)
        ),
        store.setRateLimit(acctLimitKey, recordFailedAttempt(acctState, MAX_LOGIN_FAILURES, nowMs)),
      ]);
      throw new AuthServiceError(
        'invalid-credentials',
        'اسم المستخدم أو كلمة المرور غير صحيحة.',
        401
      );
    }

    const ok = await verifyPassword(cleanPassword, cred.passwordSalt, cred.passwordHash);
    if (!ok) {
      await Promise.all([
        store.setRateLimit(
          ipLimitKey,
          recordFailedAttempt(ipState, MAX_LOGIN_FAILURES * 2, nowMs)
        ),
        store.setRateLimit(acctLimitKey, recordFailedAttempt(acctState, MAX_LOGIN_FAILURES, nowMs)),
      ]);
      throw new AuthServiceError(
        'invalid-credentials',
        'اسم المستخدم أو كلمة المرور غير صحيحة.',
        401
      );
    }

    await store.clearRateLimit(acctLimitKey);

    const customToken = await store.issueCustomToken(cred.uid, {
      username: cred.username,
      usernameLower: cred.usernameLower,
    });

    return {
      uid: cred.uid,
      username: cred.username,
      usernameLower: cred.usernameLower,
      usernameId: norm.usernameId,
      customToken,
    };
  }

  throw new AuthServiceError('invalid-action', 'عملية مصادقة غير مدعومة.', 400);
}

module.exports = {
  AuthServiceError,
  MAX_LOGIN_FAILURES,
  MAX_REGISTER_ATTEMPTS,
  RATE_WINDOW_MS,
  LOCKOUT_MS,
  normalizeUsername,
  validatePassword,
  hashPassword,
  verifyPassword,
  makeLimitId,
  evaluateRateLimit,
  recordFailedAttempt,
  executeUsernameAuth,
};
