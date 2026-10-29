import firebaseConfig from './firebaseConfig';

export interface SessionBootstrapState {
  sessionExpiredReset: boolean;
  tokenServiceDisabled: boolean;
  activationUrl: string | null;
}

const IDB_NAME = 'firebaseLocalStorageDb';
const IDB_VERSION = 1;
const IDB_STORE = 'firebaseLocalStorage';
const AUTH_STORAGE_KEY = `firebase:authUser:${firebaseConfig.apiKey}:[DEFAULT]`;

let prebootState: SessionBootstrapState = {
  sessionExpiredReset: false,
  tokenServiceDisabled: false,
  activationUrl: null,
};

export function getPrebootSessionState(): SessionBootstrapState {
  return prebootState;
}

interface StoredStsTokenManager {
  refreshToken?: string;
  accessToken?: string;
  expirationTime?: number;
}

interface StoredAuthRecord {
  fbase_key?: string;
  value?: {
    stsTokenManager?: StoredStsTokenManager;
  };
  stsTokenManager?: StoredStsTokenManager;
}

function readFromIndexedDb(): Promise<StoredAuthRecord | null> {
  if (typeof indexedDB === 'undefined') return Promise.resolve(null);
  return new Promise((resolve) => {
    try {
      const req = indexedDB.open(IDB_NAME, IDB_VERSION);
      req.onupgradeneeded = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(IDB_STORE)) {
          db.createObjectStore(IDB_STORE, { keyPath: 'fbase_key' });
        }
      };
      req.onerror = () => resolve(null);
      req.onsuccess = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(IDB_STORE)) {
          db.close();
          resolve(null);
          return;
        }
        try {
          const tx = db.transaction([IDB_STORE], 'readonly');
          const getReq = tx.objectStore(IDB_STORE).get(AUTH_STORAGE_KEY);
          getReq.onsuccess = () => {
            db.close();
            resolve((getReq.result as StoredAuthRecord | undefined) ?? null);
          };
          getReq.onerror = () => {
            db.close();
            resolve(null);
          };
        } catch {
          db.close();
          resolve(null);
        }
      };
    } catch {
      resolve(null);
    }
  });
}

function deleteFromIndexedDb(): Promise<void> {
  if (typeof indexedDB === 'undefined') return Promise.resolve();
  return new Promise((resolve) => {
    try {
      const req = indexedDB.open(IDB_NAME, IDB_VERSION);
      req.onerror = () => resolve();
      req.onsuccess = () => {
        const db = req.result;
        if (!db.objectStoreNames.contains(IDB_STORE)) {
          db.close();
          resolve();
          return;
        }
        try {
          const tx = db.transaction([IDB_STORE], 'readwrite');
          tx.objectStore(IDB_STORE).delete(AUTH_STORAGE_KEY);
          tx.oncomplete = () => {
            db.close();
            resolve();
          };
          tx.onerror = () => {
            db.close();
            resolve();
          };
        } catch {
          db.close();
          resolve();
        }
      };
    } catch {
      resolve();
    }
  });
}

/**
 * Inspects persisted Firebase Auth state BEFORE Firebase SDK modules initialize.
 * If a cached 1-hour ID token has expired and securetoken.googleapis.com cannot refresh it,
 * clears the stale cached token immediately so Firestore never fails with an offline/auth error.
 */
export async function runPrebootFirebaseAuthGuard(): Promise<SessionBootstrapState> {
  try {
    let record = await readFromIndexedDb();
    if (!record && typeof localStorage !== 'undefined') {
      const rawLs = localStorage.getItem(AUTH_STORAGE_KEY);
      if (rawLs) {
        record = JSON.parse(rawLs) as StoredAuthRecord;
      }
    }

    const sts = record?.value?.stsTokenManager ?? record?.stsTokenManager;
    if (!sts || typeof sts.expirationTime !== 'number') {
      return prebootState;
    }

    // If token still has > 60s validity, no refresh call is needed; keep session intact
    if (sts.expirationTime > Date.now() + 60_000) {
      return prebootState;
    }

    // Token is expired or expiring within 60s — probe whether Token Service API can refresh it
    let canRefresh = false;
    let tokenServiceDisabled = false;
    const projNum = firebaseConfig.messagingSenderId || firebaseConfig.projectId;
    let activationUrl = `https://console.developers.google.com/apis/api/securetoken.googleapis.com/overview?project=${projNum}`;

    if (sts.refreshToken) {
      try {
        const resp = await fetch(
          `https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(firebaseConfig.apiKey)}`,
          {
            method: 'POST',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: `grant_type=refresh_token&refresh_token=${encodeURIComponent(sts.refreshToken)}`,
          }
        );
        if (resp.ok) {
          canRefresh = true;
        } else {
          const errBody = await resp.text();
          if (
            resp.status === 403 ||
            errBody.includes('SERVICE_DISABLED') ||
            errBody.includes('securetoken.googleapis.com')
          ) {
            tokenServiceDisabled = true;
            const match = errBody.match(
              /https:\/\/console\.developers\.google\.com\/apis\/api\/securetoken\.googleapis\.com\/overview\?project=\d+/
            );
            if (match) activationUrl = match[0];
          }
        }
      } catch {
        // Network issue during refresh check
      }
    }

    if (!canRefresh) {
      await deleteFromIndexedDb();
      if (typeof localStorage !== 'undefined') {
        try {
          localStorage.removeItem(AUTH_STORAGE_KEY);
        } catch {
          // Ignore storage error
        }
      }
      prebootState = {
        sessionExpiredReset: true,
        tokenServiceDisabled,
        activationUrl: tokenServiceDisabled ? activationUrl : null,
      };
    }
  } catch {
    // Non-fatal guard
  }

  return prebootState;
}
