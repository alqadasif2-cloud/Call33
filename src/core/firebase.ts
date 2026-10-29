import { initializeApp } from 'firebase/app';
import { getAuth, signOut } from 'firebase/auth';
import { doc, getDocFromServer, getFirestore } from 'firebase/firestore';
import firebaseConfig from './firebaseConfig';
import { getPrebootSessionState, SessionBootstrapState } from './prebootAuthGuard';
import { AccountSession, FirestoreErrorInfo, OperationType } from './types';

export type { SessionBootstrapState };

const app = initializeApp(firebaseConfig);
export const db = getFirestore(app, firebaseConfig.firestoreDatabaseId);
export const auth = getAuth(app);

const ACTIVE_SESSION_KEY = 'personal_backup_active_session_v2';
type SessionListener = (session: AccountSession | null) => void;
const sessionListeners = new Set<SessionListener>();
let memoryActiveSession: AccountSession | null = null;

function readStoredSession(): AccountSession | null {
  if (memoryActiveSession) return memoryActiveSession;
  if (typeof localStorage === 'undefined') return null;
  try {
    const raw = localStorage.getItem(ACTIVE_SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as Partial<AccountSession>;
    if (
      typeof parsed.uid === 'string' &&
      parsed.uid.length >= 1 &&
      parsed.uid.length <= 128 &&
      typeof parsed.username === 'string' &&
      parsed.username.length >= 3
    ) {
      memoryActiveSession = { uid: parsed.uid, username: parsed.username };
      return memoryActiveSession;
    }
    return null;
  } catch {
    return null;
  }
}

export const ActiveSessionStore = {
  load(): AccountSession | null {
    return readStoredSession();
  },

  getActiveUid(): string | null {
    return auth.currentUser?.uid || readStoredSession()?.uid || null;
  },

  save(session: AccountSession): void {
    memoryActiveSession = session;
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.setItem(ACTIVE_SESSION_KEY, JSON.stringify(session));
      } catch {
        // Ignore storage quota errors
      }
    }
    sessionListeners.forEach((cb) => cb(session));
  },

  clear(): void {
    memoryActiveSession = null;
    if (typeof localStorage !== 'undefined') {
      try {
        localStorage.removeItem(ACTIVE_SESSION_KEY);
      } catch {
        // Ignore
      }
    }
    sessionListeners.forEach((cb) => cb(null));
  },

  subscribe(listener: SessionListener): () => void {
    sessionListeners.add(listener);
    return () => {
      sessionListeners.delete(listener);
    };
  },
};

export function isTokenServiceDisabledError(err: unknown): boolean {
  const msg = (err instanceof Error ? err.message : String(err)).toLowerCase();
  return (
    msg.includes('token-service-api-has-not-been-used') ||
    msg.includes('securetoken.googleapis.com')
  );
}

/**
 * Validates initial connectivity to Firestore server as required by Firebase integration guidelines.
 */
async function testConnection(): Promise<void> {
  try {
    await getDocFromServer(doc(db, 'test', 'connection'));
  } catch (error) {
    if (error instanceof Error && error.message.includes('the client is offline')) {
      console.error('Please check your Firebase configuration.');
    }
  }
}

let bootstrapPromise: Promise<SessionBootstrapState> | null = null;

export function bootstrapFirebaseSession(): Promise<SessionBootstrapState> {
  if (!bootstrapPromise) {
    bootstrapPromise = (async (): Promise<SessionBootstrapState> => {
      const initial = getPrebootSessionState();
      let sessionExpiredReset = initial.sessionExpiredReset;
      let tokenServiceDisabled = initial.tokenServiceDisabled;
      const activationUrl = initial.activationUrl;

      try {
        await auth.authStateReady();
        if (auth.currentUser) {
          try {
            await auth.currentUser.getIdToken(false);
          } catch (tokenErr) {
            if (!ActiveSessionStore.load()) {
              sessionExpiredReset = true;
            }
            if (isTokenServiceDisabledError(tokenErr)) {
              tokenServiceDisabled = true;
            }
            await signOut(auth);
          }
        }
      } catch {
        // Proceed to connectivity test
      }

      await testConnection();

      return {
        sessionExpiredReset,
        tokenServiceDisabled,
        activationUrl,
      };
    })();
  }
  return bootstrapPromise;
}

/**
 * Standardized Firestore permission/operation error handler producing structured JSON diagnostics.
 */
export function handleFirestoreError(
  error: unknown,
  operationType: OperationType,
  path: string | null
): never {
  const errInfo: FirestoreErrorInfo = {
    error: error instanceof Error ? error.message : String(error),
    authInfo: {
      userId: ActiveSessionStore.getActiveUid(),
      email: auth.currentUser?.email,
      emailVerified: auth.currentUser?.emailVerified,
      isAnonymous: auth.currentUser?.isAnonymous,
      tenantId: auth.currentUser?.tenantId,
      providerInfo:
        auth.currentUser?.providerData?.map((provider) => ({
          providerId: provider.providerId,
          email: provider.email,
        })) || [],
    },
    operationType,
    path,
  };
  console.error('Firestore Error: ', JSON.stringify(errInfo));
  throw new Error(JSON.stringify(errInfo));
}

/**
 * Maps Firebase Auth & Firestore errors to clear Arabic user-facing messages without leaking sensitive payloads.
 */
export function friendlyErrorMessageAr(err: unknown): string {
  const raw = err instanceof Error ? err.message : String(err);

  if (isTokenServiceDisabledError(err)) {
    return 'انتهت صلاحية الجلسة السابقة. يرجى تسجيل الدخول باسم المستخدم وكلمة المرور للمتابعة.';
  }

  if (raw.includes('Quota exceeded')) {
    return 'تم تجاوز حصة القراءة/الكتابة اليومية المجانية في Firestore. ستُعاد الحصة تلقائيًا في اليوم التالي.';
  }

  if (raw.startsWith('{') && raw.includes('"operationType"')) {
    try {
      const parsed = JSON.parse(raw) as FirestoreErrorInfo;
      if (isTokenServiceDisabledError(parsed.error)) {
        return 'انتهت صلاحية الجلسة السحابية؛ يرجى تسجيل الدخول مجددًا.';
      }
      if (parsed.error.includes('Quota exceeded')) {
        return 'تم تجاوز حصة الاستخدام اليومية المجانية لقاعدة البيانات السحابية.';
      }
      if (parsed.error.includes('Missing or insufficient permissions')) {
        return 'رفضت قواعد الأمان السحابية هذه العملية. تأكد من صلاحية الجلسة وأن البيانات تتبع لحسابك.';
      }
      if (parsed.error.includes('offline') || parsed.error.includes('unavailable')) {
        return 'تعذّر الوصول إلى الخادم السحابي حاليًا. تحقق من اتصال الإنترنت وأعد المحاولة.';
      }
    } catch {
      // Fall through
    }
  }

  if (raw.includes('auth/email-already-in-use') || raw.includes('username-already-in-use')) {
    return 'اسم المستخدم مستخدم بالفعل. اختر اسمًا آخر.';
  }
  if (
    raw.includes('auth/invalid-credential') ||
    raw.includes('auth/wrong-password') ||
    raw.includes('auth/user-not-found') ||
    raw.includes('auth/invalid-login-credentials') ||
    raw.includes('invalid-credentials')
  ) {
    return 'اسم المستخدم أو كلمة المرور غير صحيحة.';
  }
  if (raw.includes('auth/weak-password')) {
    return 'يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.';
  }
  if (raw.includes('auth/too-many-requests') || raw.includes('too-many-requests')) {
    return 'تم إيقاف المحاولات مؤقتًا لكثرة المحاولات الخاطئة. يرجى الانتظار قليلًا ثم المحاولة مجددًا.';
  }
  if (raw.includes('auth/network-request-failed')) {
    return 'لا يوجد اتصال بالإنترنت. تحقق من الشبكة وأعد المحاولة.';
  }
  if (raw.includes('auth/requires-recent-login')) {
    return 'يلزم تأكيد تسجيل دخول حديث لإتمام حذف الحساب.';
  }

  return raw.length <= 180 && /[\u0600-\u06FF]/.test(raw)
    ? raw
    : 'تعذّر إتمام العملية السحابية. تحقق من الاتصال وأعد المحاولة.';
}
