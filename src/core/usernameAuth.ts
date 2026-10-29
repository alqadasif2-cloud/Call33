import { signInWithCustomToken, signOut as firebaseSignOut } from 'firebase/auth';
import firebaseConfig from './firebaseConfig';
import { BLUEPRINT_LIMITS, CloudRepo } from './cloudRepo';
import { deriveCapabilityUid, KeyVault, sha256Hex } from './crypto';
import { ActiveSessionStore, auth } from './firebase';
import { LocalDevicePrefs, LocalPrefsStore } from './localQueue';
import { AccountSession, NormalizedUsername } from './types';

const RATE_LIMIT_KEY = 'personal_backup_auth_rate_limit_v1';
const LAST_USERNAME_KEY = 'personal_backup_last_username_v1';
const RATE_WINDOW_MS = 15 * 60 * 1000;
const LOCKOUT_MS = 15 * 60 * 1000;
const MAX_ATTEMPTS = 5;
const CLOUD_FUNCTION_REGION = 'us-central1';

interface ClientRateState {
  attempts: number;
  windowStartMs: number;
  blockedUntilMs: number;
}

export interface AuthSessionResult {
  session: AccountSession;
  vaultKey: CryptoKey;
  prefs: LocalDevicePrefs;
}

type CustomFunctionAvailability = 'unknown' | 'deployed' | 'not_deployed';
let customFunctionState: CustomFunctionAvailability = 'unknown';

function readRateState(): ClientRateState | null {
  if (typeof localStorage === 'undefined') return null;
  try {
    const raw = localStorage.getItem(RATE_LIMIT_KEY);
    if (!raw) return null;
    return JSON.parse(raw) as ClientRateState;
  } catch {
    return null;
  }
}

function checkClientRateLimit(nowMs = Date.now()): void {
  const state = readRateState();
  if (state && state.blockedUntilMs > nowMs) {
    const mins = Math.max(1, Math.ceil((state.blockedUntilMs - nowMs) / 60_000));
    throw new Error(
      `تم إيقاف المحاولات مؤقتًا لكثرة المحاولات الخاطئة. يرجى الانتظار ${mins} دقيقة ثم المحاولة مجددًا.`
    );
  }
}

function recordClientFailure(nowMs = Date.now()): void {
  if (typeof localStorage === 'undefined') return;
  try {
    const prev = readRateState();
    const expired = !prev || nowMs - prev.windowStartMs > RATE_WINDOW_MS;
    const attempts = (expired ? 0 : prev.attempts) + 1;
    const windowStartMs = expired ? nowMs : prev.windowStartMs;
    const blockedUntilMs = attempts >= MAX_ATTEMPTS ? nowMs + LOCKOUT_MS : 0;
    localStorage.setItem(
      RATE_LIMIT_KEY,
      JSON.stringify({ attempts, windowStartMs, blockedUntilMs })
    );
  } catch {
    // Ignore storage quota errors
  }
}

function clearClientFailures(): void {
  if (typeof localStorage === 'undefined') return;
  try {
    localStorage.removeItem(RATE_LIMIT_KEY);
  } catch {
    // Ignore
  }
}

/**
 * Automatically derives the project's Cloud Function HTTPS endpoint directly from
 * `firebase-applet-config.json` without asking the user for any manual URL or key.
 */
export function resolveProjectCustomAuthEndpoint(): string {
  const projectId = String(firebaseConfig.projectId || '').trim();
  if (!projectId || projectId === 'remixed-project-id') {
    return '';
  }
  return `https://${CLOUD_FUNCTION_REGION}-${projectId}.cloudfunctions.net/authUsername`;
}

/**
 * Invokes the project's `authUsername` Cloud Function if deployed on `firebaseConfig.projectId`.
 * Returns `null` when the Cloud Function is not deployed so the client seamlessly executes
 * Direct Cloud Capability Authentication against Firestore without requiring Firebase Console setup.
 */
async function tryInvokeProjectCustomAuth(
  payload: Record<string, string>,
  idToken?: string
): Promise<Record<string, unknown> | null> {
  if (customFunctionState === 'not_deployed') {
    return null;
  }

  const endpoint = resolveProjectCustomAuthEndpoint();
  if (!endpoint) {
    return null;
  }

  const headers: Record<string, string> = {
    'Content-Type': 'application/json; charset=utf-8',
  };
  if (idToken) {
    headers.Authorization = `Bearer ${idToken}`;
  }

  let resp: Response;
  try {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 5000);
    try {
      resp = await fetch(endpoint, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
        signal: controller.signal,
      });
    } finally {
      clearTimeout(timer);
    }
  } catch {
    if (customFunctionState === 'deployed') {
      throw new Error('لا يوجد اتصال بالإنترنت. تحقق من الشبكة وأعد المحاولة.');
    }
    return null;
  }

  const contentType = (resp.headers.get('content-type') || '').toLowerCase();
  if ((resp.status === 404 || resp.status === 403) && !contentType.includes('application/json')) {
    customFunctionState = 'not_deployed';
    return null;
  }

  const json = (await resp.json().catch(() => null)) as Record<string, unknown> | null;
  if (!json || typeof json.ok !== 'boolean') {
    if (customFunctionState !== 'deployed') {
      customFunctionState = 'not_deployed';
      return null;
    }
    throw new Error(`تعذّرت المصادقة مع الخادم (${resp.status}).`);
  }

  customFunctionState = 'deployed';
  if (!resp.ok || json.ok !== true) {
    const errMsg =
      typeof json.error === 'string' && json.error
        ? json.error
        : `تعذّرت المصادقة مع الخادم (${resp.status}).`;
    throw new Error(errMsg);
  }

  return json;
}

let activeAuthInProgress = false;

export const UsernameAuth = {
  get inProgress(): boolean {
    return activeAuthInProgress;
  },

  getSavedUsername(): string {
    if (typeof localStorage === 'undefined') return '';
    try {
      return localStorage.getItem(LAST_USERNAME_KEY) || '';
    } catch {
      return '';
    }
  },

  saveUsername(username: string): void {
    if (typeof localStorage === 'undefined') return;
    try {
      localStorage.setItem(LAST_USERNAME_KEY, username);
    } catch {
      // Ignore
    }
  },

  async normalizeUsername(raw: string): Promise<NormalizedUsername> {
    const trimmed = raw.trim();
    if (
      trimmed.length < BLUEPRINT_LIMITS.USERNAME_MIN_LEN ||
      trimmed.length > BLUEPRINT_LIMITS.USERNAME_MAX_LEN
    ) {
      throw new Error('يجب أن يتكون اسم المستخدم من 3 إلى 32 حرفًا أو رقمًا دون مسافات.');
    }
    if (!BLUEPRINT_LIMITS.USERNAME_PATTERN.test(trimmed)) {
      throw new Error('اسم المستخدم يقبل الحروف والأرقام والنقطة والشرطة فقط دون مسافات.');
    }
    const usernameLower = trimmed.toLowerCase();
    const hex = await sha256Hex(`uname:${usernameLower}`);
    const usernameId = hex.slice(0, 40);
    return {
      username: trimmed,
      usernameLower,
      usernameId,
      syntheticEmail: `u_${usernameId}@users.personalbackup.app`,
    };
  },

  validatePassword(raw: string): string {
    if (raw.length < 8) {
      throw new Error('يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.');
    }
    if (raw.length > 128) {
      throw new Error('كلمة المرور تتجاوز الحد الأقصى المسموح به (128 حرفًا).');
    }
    return raw;
  },

  async register(rawUsername: string, rawPassword: string): Promise<AuthSessionResult> {
    const norm = await UsernameAuth.normalizeUsername(rawUsername);
    const validPw = UsernameAuth.validatePassword(rawPassword);
    checkClientRateLimit();

    activeAuthInProgress = true;
    try {
      const customRes = await tryInvokeProjectCustomAuth({
        action: 'register',
        username: norm.username,
        password: validPw,
      });

      let uid: string;
      let key: CryptoKey | null;

      if (customRes) {
        const cred = await signInWithCustomToken(auth, String(customRes.customToken));
        uid = cred.user.uid;
        await CloudRepo.ensureUserProfile(norm, false);
        const hasVault = await CloudRepo.hasCrypto(uid);
        key = hasVault
          ? await CloudRepo.unlock(validPw, uid)
          : await CloudRepo.createCrypto(validPw, uid);
      } else {
        uid = await deriveCapabilityUid(norm.usernameId, validPw);
        key = await CloudRepo.registerCapabilityAccount(uid, norm, validPw);
      }

      if (!key) {
        throw new Error('تعذّر إعداد مفتاح التشفير للحساب.');
      }

      const session: AccountSession = { uid, username: norm.username };
      await KeyVault.save(uid, key);
      ActiveSessionStore.save(session);

      const loadedPrefs = LocalPrefsStore.load(uid);
      const nextPrefs: LocalDevicePrefs = {
        ...loadedPrefs,
        onboardingDone: true,
      };
      LocalPrefsStore.save(uid, nextPrefs);
      await CloudRepo.registerOrUpdateDevice(
        nextPrefs.deviceId,
        nextPrefs.deviceName,
        nextPrefs.model,
        nextPrefs.enabled,
        uid
      );

      UsernameAuth.saveUsername(norm.username);
      clearClientFailures();

      return {
        session,
        vaultKey: key,
        prefs: nextPrefs,
      };
    } catch (err) {
      recordClientFailure();
      throw err;
    } finally {
      activeAuthInProgress = false;
    }
  },

  async login(rawUsername: string, rawPassword: string): Promise<AuthSessionResult> {
    const norm = await UsernameAuth.normalizeUsername(rawUsername);
    const validPw = UsernameAuth.validatePassword(rawPassword);
    checkClientRateLimit();

    activeAuthInProgress = true;
    try {
      const customRes = await tryInvokeProjectCustomAuth({
        action: 'login',
        username: norm.username,
        password: validPw,
      });

      let uid: string;
      let resolvedUsername = norm.username;
      let key: CryptoKey | null;

      if (customRes) {
        const cred = await signInWithCustomToken(auth, String(customRes.customToken));
        uid = cred.user.uid;
        await CloudRepo.ensureUserProfile(norm, false);
        const hasVault = await CloudRepo.hasCrypto(uid);
        key = hasVault
          ? await CloudRepo.unlock(validPw, uid)
          : await CloudRepo.createCrypto(validPw, uid);
        resolvedUsername = (await CloudRepo.getCurrentUsername(uid)) || norm.username;
      } else {
        uid = await deriveCapabilityUid(norm.usernameId, validPw);
        const storedUsername = await CloudRepo.lookupCapabilityAccountUsername(uid);
        if (!storedUsername) {
          throw new Error('اسم المستخدم أو كلمة المرور غير صحيحة.');
        }
        resolvedUsername = storedUsername;
        const hasVault = await CloudRepo.hasCrypto(uid);
        key = hasVault
          ? await CloudRepo.unlock(validPw, uid)
          : await CloudRepo.createCrypto(validPw, uid);
      }

      if (!key) {
        if (auth.currentUser) {
          await firebaseSignOut(auth);
        }
        throw new Error('اسم المستخدم أو كلمة المرور غير صحيحة.');
      }

      const session: AccountSession = { uid, username: resolvedUsername };
      await KeyVault.save(uid, key);
      ActiveSessionStore.save(session);

      const loadedPrefs = LocalPrefsStore.load(uid);
      const nextPrefs: LocalDevicePrefs = {
        ...loadedPrefs,
        onboardingDone: true,
      };
      if (!loadedPrefs.onboardingDone) {
        LocalPrefsStore.save(uid, nextPrefs);
        await CloudRepo.registerOrUpdateDevice(
          nextPrefs.deviceId,
          nextPrefs.deviceName,
          nextPrefs.model,
          nextPrefs.enabled,
          uid
        );
      }

      UsernameAuth.saveUsername(resolvedUsername);
      clearClientFailures();

      return {
        session,
        vaultKey: key,
        prefs: nextPrefs,
      };
    } catch (err) {
      recordClientFailure();
      throw err;
    } finally {
      activeAuthInProgress = false;
    }
  },

  async deleteCustomAuthAccountIfConfigured(password: string): Promise<boolean> {
    const current = auth.currentUser;
    if (!current) return false;
    const isPasswordProvider = current.providerData.some((p) => p.providerId === 'password');
    if (isPasswordProvider) {
      return false;
    }
    const idToken = await current.getIdToken(false);
    const res = await tryInvokeProjectCustomAuth({ action: 'deleteAccount', password }, idToken);
    if (!res) return false;
    await firebaseSignOut(auth);
    return true;
  },

  async signOut(): Promise<void> {
    const uid = ActiveSessionStore.getActiveUid();
    if (uid) {
      await KeyVault.clear(uid);
    }
    ActiveSessionStore.clear();
    if (auth.currentUser) {
      await firebaseSignOut(auth);
    }
  },
};
