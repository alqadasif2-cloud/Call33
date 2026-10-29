import { isValidPlainRecord } from './importer';
import {
  LocalQueuedRecord,
  PlainRecord,
  RecordFields,
  RecordType,
  SyncErrorDiagnostic,
  SyncStateCode,
} from './types';

/**
 * Local Staging Database & Preferences (Port of Db.kt + Prefs.kt)
 * Uses IndexedDB for local record storage with deterministic SHA-256 deduplication (IGNORE on duplicate ID)
 * and tracks all 4 explicit sync states:
 * - 0: بانتظار الرفع (Pending upload)
 * - 1: تمت مزامنته (Confirmed by Firestore batch commit)
 * - 2: فشل الرفع (Failed upload — retried without re-uploading synced records)
 * - 3: محفوظ محليًا (Saved locally while paused)
 */
const DB_NAME = 'cal1_local_records_v1';
const STORE_RECORDS = 'records';

export function syncStateLabelAr(state: SyncStateCode): string {
  switch (state) {
    case 1:
      return 'تمت مزامنته';
    case 2:
      return 'فشل الرفع';
    case 3:
      return 'محفوظ محليًا';
    default:
      return 'بانتظار الرفع';
  }
}

function openLocalDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);
    req.onupgradeneeded = () => {
      const db = req.result;
      if (!db.objectStoreNames.contains(STORE_RECORDS)) {
        const store = db.createObjectStore(STORE_RECORDS, { keyPath: ['uid', 'id'] });
        store.createIndex('by_uid', 'uid', { unique: false });
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export interface LocalDevicePrefs {
  deviceId: string;
  deviceName: string;
  model: string;
  enabled: RecordType[];
  paused: boolean;
  onboardingDone: boolean;
  lastSyncAt: number;
  lastScanAt: number;
  autoClipCapture: boolean;
  lastSyncError: SyncErrorDiagnostic | null;
}

function detectBrowserModel(): string {
  const ua = navigator.userAgent;
  if (ua.includes('Edg/')) return 'Microsoft Edge Web';
  if (ua.includes('Chrome/')) return 'Chrome Web Vault';
  if (ua.includes('Firefox/')) return 'Firefox Web Vault';
  if (ua.includes('Safari/')) return 'Safari Web Vault';
  return 'Web Browser Vault';
}

function generateCleanDeviceId(): string {
  const bytes = new Uint8Array(8);
  crypto.getRandomValues(bytes);
  const hex = Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
  return `web_${hex}`;
}

export const LocalPrefsStore = {
  key(uid: string): string {
    return `cal1_prefs_${uid}`;
  },

  load(uid: string): LocalDevicePrefs {
    try {
      const raw = localStorage.getItem(LocalPrefsStore.key(uid));
      if (raw) {
        const parsed = JSON.parse(raw) as Partial<LocalDevicePrefs>;
        if (parsed.deviceId && /^[a-zA-Z0-9_-]+$/.test(parsed.deviceId)) {
          return {
            deviceId: parsed.deviceId,
            deviceName: (parsed.deviceName || 'متصفح الويب الشخصي').slice(0, 100),
            model: (parsed.model || detectBrowserModel()).slice(0, 100),
            enabled: Array.isArray(parsed.enabled)
              ? parsed.enabled.filter((t): t is RecordType =>
                  [RecordType.CALL, RecordType.SMS, RecordType.CLIP].includes(t as RecordType)
                )
              : [RecordType.CALL, RecordType.SMS, RecordType.CLIP],
            paused: Boolean(parsed.paused),
            onboardingDone: Boolean(parsed.onboardingDone),
            lastSyncAt: Number(parsed.lastSyncAt || 0),
            lastScanAt: Number(parsed.lastScanAt || 0),
            autoClipCapture: parsed.autoClipCapture !== false,
            lastSyncError:
              parsed.lastSyncError &&
              typeof parsed.lastSyncError.summary === 'string' &&
              parsed.lastSyncError.summary.length > 0
                ? {
                    summary: parsed.lastSyncError.summary,
                    remediation: String(parsed.lastSyncError.remediation || ''),
                    occurredAt: Number(parsed.lastSyncError.occurredAt || Date.now()),
                  }
                : null,
          };
        }
      }
    } catch {
      // Fall through to defaults
    }

    const fresh: LocalDevicePrefs = {
      deviceId: generateCleanDeviceId(),
      deviceName: 'خزنة المتصفح الشخصية',
      model: detectBrowserModel(),
      enabled: [RecordType.CALL, RecordType.SMS, RecordType.CLIP],
      paused: false,
      onboardingDone: false,
      lastSyncAt: 0,
      lastScanAt: 0,
      autoClipCapture: true,
      lastSyncError: null,
    };
    LocalPrefsStore.save(uid, fresh);
    return fresh;
  },

  save(uid: string, prefs: LocalDevicePrefs): void {
    try {
      localStorage.setItem(LocalPrefsStore.key(uid), JSON.stringify(prefs));
    } catch {
      // Ignore storage quota errors
    }
  },

  clear(uid: string): void {
    try {
      localStorage.removeItem(LocalPrefsStore.key(uid));
    } catch {
      // Ignore
    }
  },
};

export const LocalQueueDb = {
  /**
   * Inserts valid records using IGNORE semantics on duplicate [uid, id].
   * Returns exact count of newly inserted records.
   */
  async insertIgnore(
    uid: string,
    records: PlainRecord[],
    initialState: SyncStateCode = 0
  ): Promise<number> {
    const valid = records.filter((r) => isValidPlainRecord(r));
    if (valid.length === 0) return 0;

    const db = await openLocalDb();
    let inserted = 0;

    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      const now = Date.now();

      for (const r of valid) {
        const checkReq = store.get([uid, r.id]);
        checkReq.onsuccess = () => {
          if (!checkReq.result) {
            const entity: LocalQueuedRecord = {
              id: r.id,
              uid,
              deviceId: r.deviceId,
              type: r.type,
              ts: r.ts,
              json: JSON.stringify(r.fields),
              syncState: initialState,
              createdAt: now,
              attemptCount: 0,
              lastError: null,
            };
            store.put(entity);
            inserted++;
          }
        };
      }

      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });

    db.close();
    return inserted;
  },

  async listAll(uid: string): Promise<LocalQueuedRecord[]> {
    const db = await openLocalDb();
    const rows = await new Promise<LocalQueuedRecord[]>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readonly');
      const index = tx.objectStore(STORE_RECORDS).index('by_uid');
      const req = index.getAll(IDBKeyRange.only(uid));
      req.onsuccess = () => resolve((req.result as LocalQueuedRecord[]) ?? []);
      req.onerror = () => reject(req.error);
    });
    db.close();
    return rows.sort((a, b) => b.ts - a.ts);
  },

  async pendingBatch(uid: string, limit = 150): Promise<LocalQueuedRecord[]> {
    const all = await LocalQueueDb.listAll(uid);
    return all
      .filter((r) => r.syncState === 0 || r.syncState === 2)
      .sort((a, b) => a.ts - b.ts)
      .slice(0, limit);
  },

  async promoteLocalToPending(uid: string): Promise<number> {
    const db = await openLocalDb();
    let count = 0;
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      const index = store.index('by_uid');
      const req = index.openCursor(IDBKeyRange.only(uid));
      req.onsuccess = () => {
        const cursor = req.result;
        if (cursor) {
          const rec = cursor.value as LocalQueuedRecord;
          if (rec.syncState === 3) {
            rec.syncState = 0;
            cursor.update(rec);
            count++;
          }
          cursor.continue();
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
    return count;
  },

  async markSynced(uid: string, ids: string[]): Promise<void> {
    if (ids.length === 0) return;
    const db = await openLocalDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      for (const id of ids) {
        const getReq = store.get([uid, id]);
        getReq.onsuccess = () => {
          const existing = getReq.result as LocalQueuedRecord | undefined;
          if (existing) {
            existing.syncState = 1;
            existing.lastError = null;
            store.put(existing);
          }
        };
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  },

  async markFailed(uid: string, ids: string[], reason: string): Promise<void> {
    if (ids.length === 0) return;
    const db = await openLocalDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      for (const id of ids) {
        const getReq = store.get([uid, id]);
        getReq.onsuccess = () => {
          const existing = getReq.result as LocalQueuedRecord | undefined;
          if (existing && existing.syncState !== 1) {
            existing.syncState = 2;
            existing.attemptCount = (existing.attemptCount ?? 0) + 1;
            existing.lastError = reason;
            store.put(existing);
          }
        };
      }
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  },

  async retryFailedNow(uid: string): Promise<number> {
    const db = await openLocalDb();
    let count = 0;
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      const index = store.index('by_uid');
      const req = index.openCursor(IDBKeyRange.only(uid));
      req.onsuccess = () => {
        const cursor = req.result;
        if (cursor) {
          const rec = cursor.value as LocalQueuedRecord;
          if (rec.syncState === 2) {
            rec.syncState = 0;
            cursor.update(rec);
            count++;
          }
          cursor.continue();
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
    return count;
  },

  async resetAllToPending(uid: string): Promise<number> {
    const db = await openLocalDb();
    let count = 0;
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      const index = store.index('by_uid');
      const req = index.openCursor(IDBKeyRange.only(uid));
      req.onsuccess = () => {
        const cursor = req.result;
        if (cursor) {
          const rec = cursor.value as LocalQueuedRecord;
          rec.syncState = 0;
          rec.lastError = null;
          cursor.update(rec);
          count++;
          cursor.continue();
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
    return count;
  },

  async deleteOne(uid: string, id: string): Promise<void> {
    const db = await openLocalDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      tx.objectStore(STORE_RECORDS).delete([uid, id]);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  },

  async deleteAllForUser(uid: string): Promise<void> {
    const db = await openLocalDb();
    await new Promise<void>((resolve, reject) => {
      const tx = db.transaction(STORE_RECORDS, 'readwrite');
      const store = tx.objectStore(STORE_RECORDS);
      const index = store.index('by_uid');
      const req = index.openCursor(IDBKeyRange.only(uid));
      req.onsuccess = () => {
        const cursor = req.result;
        if (cursor) {
          cursor.delete();
          cursor.continue();
        }
      };
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
    db.close();
  },

  toPlainRecord(row: LocalQueuedRecord): PlainRecord {
    let fields: RecordFields = {};
    try {
      fields = JSON.parse(row.json) as RecordFields;
    } catch {
      fields = {};
    }
    return {
      id: row.id,
      deviceId: row.deviceId,
      type: row.type,
      ts: row.ts,
      fields,
    };
  },
};
