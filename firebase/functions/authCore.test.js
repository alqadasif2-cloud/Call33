'use strict';

const { test } = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const {
  AuthServiceError,
  MAX_LOGIN_FAILURES,
  LOCKOUT_MS,
  normalizeUsername,
  hashPassword,
  verifyPassword,
  executeUsernameAuth,
} = require('./authCore');

function createInMemoryStore() {
  const credentials = new Map();
  const usernames = new Map();
  const users = new Map();
  const rateLimits = new Map();

  return {
    credentials,
    usernames,
    users,
    rateLimits,
    async getRateLimit(key) {
      return rateLimits.get(key) || null;
    },
    async setRateLimit(key, val) {
      rateLimits.set(key, val);
    },
    async clearRateLimit(key) {
      rateLimits.delete(key);
    },
    async getCredential(usernameId) {
      return credentials.get(usernameId) || null;
    },
    async createAuthAccount({
      username,
      usernameLower,
      usernameId,
      alg,
      passwordSalt,
      passwordHash,
      nowMs,
    }) {
      if (usernames.has(usernameId) || credentials.has(usernameId)) {
        throw new AuthServiceError(
          'username-already-in-use',
          'اسم المستخدم مستخدم بالفعل. اختر اسمًا آخر.',
          409
        );
      }
      const uid = `u_${crypto.randomBytes(14).toString('hex')}`;
      usernames.set(usernameId, { uid, usernameLower, createdAt: nowMs });
      credentials.set(usernameId, {
        uid,
        username,
        usernameLower,
        alg,
        passwordSalt,
        passwordHash,
        createdAt: nowMs,
      });
      users.set(uid, { uid, username, usernameLower, usernameId, createdAt: nowMs });
      return uid;
    },
    async issueCustomToken(uid, claims) {
      return `custom_token.${uid}.${claims.usernameLower}`;
    },
  };
}

test('تجزئة كلمة المرور عبر scrypt لا تخزن النص العادي وتتحقق بزمن ثابت', async () => {
  const hashed = await hashPassword('MyBackup2026!');
  assert.equal(hashed.alg, 'scrypt-v1');
  assert.ok(!hashed.passwordHash.includes('MyBackup2026!'));
  assert.equal(hashed.passwordSalt.length, 64);
  assert.equal(hashed.passwordHash.length, 128);

  assert.equal(await verifyPassword('MyBackup2026!', hashed.passwordSalt, hashed.passwordHash), true);
  assert.equal(await verifyPassword('WrongPass2026!', hashed.passwordSalt, hashed.passwordHash), false);
});

test('إنشاء حساب sami123 في التطبيق الأول وتسجيل الدخول في التطبيق الثاني يعيد نفس UID', async () => {
  const store = createInMemoryStore();

  // 1. التسجيل في backup-app
  const reg = await executeUsernameAuth({
    action: 'register',
    username: 'sami123',
    password: 'MyBackup2026!',
    clientIp: '192.0.2.10',
    store,
  });

  assert.equal(reg.username, 'sami123');
  assert.ok(reg.uid.startsWith('u_'));
  assert.equal(store.users.get(reg.uid).username, 'sami123');
  assert.equal(store.credentials.get(reg.usernameId).password, undefined);

  // 2. تسجيل الدخول في viewer-app بنفس البيانات
  const login = await executeUsernameAuth({
    action: 'login',
    username: 'sami123',
    password: 'MyBackup2026!',
    clientIp: '192.0.2.25',
    store,
  });

  assert.equal(login.uid, reg.uid);
  assert.equal(login.username, 'sami123');
  assert.ok(login.customToken.includes(reg.uid));
});

test('منع تكرار أسماء المستخدمين بغض النظر عن حالة الأحرف', async () => {
  const store = createInMemoryStore();
  await executeUsernameAuth({
    action: 'register',
    username: 'sami123',
    password: 'MyBackup2026!',
    clientIp: '192.0.2.10',
    store,
  });

  await assert.rejects(
    () =>
      executeUsernameAuth({
        action: 'register',
        username: 'SAMI123',
        password: 'AnotherPassword99!',
        clientIp: '192.0.2.11',
        store,
      }),
    (err) => err instanceof AuthServiceError && err.code === 'username-already-in-use'
  );
});

test('حماية تسجيل الدخول من محاولات التخمين المتكررة (Rate Limiting)', async () => {
  const store = createInMemoryStore();
  const t0 = 1_700_000_000_000;

  await executeUsernameAuth({
    action: 'register',
    username: 'sami123',
    password: 'MyBackup2026!',
    clientIp: '192.0.2.10',
    nowMs: t0,
    store,
  });

  for (let i = 0; i < MAX_LOGIN_FAILURES; i++) {
    await assert.rejects(
      () =>
        executeUsernameAuth({
          action: 'login',
          username: 'sami123',
          password: `WrongGuess_${i}_12345`,
          clientIp: '198.51.100.7',
          nowMs: t0 + i * 1000,
          store,
        }),
      (err) => err instanceof AuthServiceError && err.code === 'invalid-credentials'
    );
  }

  // المحاولة السادسة تُحظر حتى لو كانت كلمة المرور صحيحة
  await assert.rejects(
    () =>
      executeUsernameAuth({
        action: 'login',
        username: 'sami123',
        password: 'MyBackup2026!',
        clientIp: '198.51.100.7',
        nowMs: t0 + 10_000,
        store,
      }),
    (err) => err instanceof AuthServiceError && err.code === 'too-many-requests' && err.status === 429
  );

  // بعد انتهاء مدة الحظر ينجح الدخول مجددًا
  const afterLockout = await executeUsernameAuth({
    action: 'login',
    username: 'sami123',
    password: 'MyBackup2026!',
    clientIp: '198.51.100.7',
    nowMs: t0 + LOCKOUT_MS + 60_000,
    store,
  });
  assert.equal(afterLockout.username, 'sami123');
});

test('عزل الحسابات: كل مستخدم يحصل على UID فريد مستقل تمامًا', async () => {
  const store = createInMemoryStore();
  const u1 = await executeUsernameAuth({
    action: 'register',
    username: 'sami123',
    password: 'MyBackup2026!',
    store,
  });
  const u2 = await executeUsernameAuth({
    action: 'register',
    username: 'khalid99',
    password: 'StrongPass99!',
    store,
  });

  assert.notEqual(u1.uid, u2.uid);
  assert.notEqual(u1.usernameId, u2.usernameId);
  assert.equal(normalizeUsername('sami123').usernameId.length, 40);
});
