'use strict';

const crypto = require('node:crypto');
const { onRequest } = require('firebase-functions/v2/https');
const admin = require('firebase-admin');
const { FieldValue, getFirestore } = require('firebase-admin/firestore');
const {
  AuthServiceError,
  executeUsernameAuth,
  verifyPassword,
} = require('./authCore');

if (!admin.apps.length) {
  admin.initializeApp();
}

const DEFAULT_FIRESTORE_DATABASE_ID = 'ai-studio-9b45ca49-21a0-4eb7-af13-f761f2dd91e9';

function resolveFirestore() {
  if (process.env.FUNCTIONS_EMULATOR === 'true' && !process.env.FIRESTORE_DATABASE_ID) {
    return getFirestore(admin.app());
  }
  const dbId = process.env.FIRESTORE_DATABASE_ID || DEFAULT_FIRESTORE_DATABASE_ID;
  if (dbId && dbId !== '(default)') {
    return getFirestore(admin.app(), dbId);
  }
  return getFirestore(admin.app());
}

function buildAdminStore(db) {
  return {
    async getRateLimit(limitKey) {
      const snap = await db.collection('authRateLimits').doc(limitKey).get();
      return snap.exists ? snap.data() : null;
    },

    async setRateLimit(limitKey, state) {
      await db.collection('authRateLimits').doc(limitKey).set(state);
    },

    async clearRateLimit(limitKey) {
      await db.collection('authRateLimits').doc(limitKey).delete();
    },

    async getCredential(usernameId) {
      const snap = await db.collection('authCredentials').doc(usernameId).get();
      return snap.exists ? snap.data() : null;
    },

    async createAuthAccount({
      username,
      usernameLower,
      usernameId,
      alg,
      passwordSalt,
      passwordHash,
    }) {
      const uid = `u_${crypto.randomBytes(14).toString('hex')}`;
      const unameRef = db.collection('usernames').doc(usernameId);
      const credRef = db.collection('authCredentials').doc(usernameId);
      const userRef = db.collection('users').doc(uid);

      await db.runTransaction(async (tx) => {
        const [unameSnap, credSnap] = await Promise.all([tx.get(unameRef), tx.get(credRef)]);
        if (unameSnap.exists || credSnap.exists) {
          throw new AuthServiceError(
            'username-already-in-use',
            'اسم المستخدم مستخدم بالفعل. اختر اسمًا آخر.',
            409
          );
        }

        tx.set(unameRef, {
          uid,
          usernameLower,
          createdAt: FieldValue.serverTimestamp(),
        });

        tx.set(credRef, {
          uid,
          username,
          usernameLower,
          passwordHash,
          passwordSalt,
          alg,
          createdAt: FieldValue.serverTimestamp(),
        });

        tx.set(userRef, {
          uid,
          username,
          usernameLower,
          usernameId,
          createdAt: FieldValue.serverTimestamp(),
        });
      });

      try {
        await admin.auth().createUser({
          uid,
          displayName: username,
        });
      } catch (err) {
        // Rollback Firestore documents if Auth user creation fails unexpectedly
        await Promise.allSettled([unameRef.delete(), credRef.delete(), userRef.delete()]);
        throw err;
      }

      return uid;
    },

    async issueCustomToken(uid, claims) {
      return admin.auth().createCustomToken(uid, claims);
    },
  };
}

/**
 * Firebase Custom Authentication HTTPS Endpoint (CORS-enabled for Web + Android backup-app & viewer-app).
 * Actions:
 *   - POST { action: 'register', username, password } -> { ok: true, uid, username, customToken }
 *   - POST { action: 'login', username, password }    -> { ok: true, uid, username, customToken }
 *   - POST { action: 'deleteAccount', password } (with Authorization: Bearer <idToken>) -> { ok: true }
 */
exports.authUsername = onRequest(
  { region: 'us-central1', cors: true, maxInstances: 10 },
  async (req, res) => {
  if (req.method !== 'POST') {
    res.status(405).json({ ok: false, code: 'method-not-allowed', error: 'الطريقة غير مسموحة.' });
    return;
  }

  const db = resolveFirestore();
  const body = typeof req.body === 'object' && req.body !== null ? req.body : {};
  const action = String(body.action || '').trim();
  const forwarded = String(req.headers['x-forwarded-for'] || '').split(',')[0].trim();
  const clientIp = forwarded || req.ip || 'unknown';

  try {
    if (action === 'deleteAccount') {
      const authHeader = String(req.headers.authorization || '');
      if (!authHeader.startsWith('Bearer ')) {
        throw new AuthServiceError('unauthenticated', 'يلزم تسجيل الدخول أولًا.', 401);
      }
      const idToken = authHeader.slice('Bearer '.length).trim();
      const decoded = await admin.auth().verifyIdToken(idToken);
      const uid = decoded.uid;

      const userSnap = await db.collection('users').doc(uid).get();
      if (!userSnap.exists) {
        throw new AuthServiceError('user-not-found', 'الحساب غير موجود.', 404);
      }
      const { usernameId } = userSnap.data();
      const credSnap = await db.collection('authCredentials').doc(usernameId).get();
      if (!credSnap.exists) {
        throw new AuthServiceError('user-not-found', 'سجل بيانات الحساب غير موجود.', 404);
      }
      const cred = credSnap.data();
      const valid = await verifyPassword(
        String(body.password || ''),
        cred.passwordSalt,
        cred.passwordHash
      );
      if (!valid) {
        throw new AuthServiceError('invalid-credentials', 'كلمة المرور غير صحيحة.', 401);
      }

      await Promise.all([
        db.collection('authCredentials').doc(usernameId).delete(),
        db.collection('usernames').doc(usernameId).delete(),
        db.collection('users').doc(uid).delete(),
      ]);
      await admin.auth().deleteUser(uid);
      res.status(200).json({ ok: true });
      return;
    }

    const result = await executeUsernameAuth({
      action,
      username: body.username,
      password: body.password,
      clientIp,
      store: buildAdminStore(db),
    });

    res.status(200).json({
      ok: true,
      uid: result.uid,
      username: result.username,
      usernameLower: result.usernameLower,
      usernameId: result.usernameId,
      customToken: result.customToken,
    });
  } catch (err) {
    if (err instanceof AuthServiceError) {
      res.status(err.status).json({
        ok: false,
        code: err.code,
        error: err.messageAr,
      });
      return;
    }
    console.error('Unhandled authUsername error:', err);
    res.status(500).json({
      ok: false,
      code: 'internal-error',
      error: 'تعذّر إتمام المصادقة على الخادم. حاول مجددًا.',
    });
  }
});
