import { EncRecord, PlainRecord, RecordFields, RecordType } from './types';

/**
 * End-to-End Encryption & Capability Derivation Engine (Web Crypto API)
 * Byte-for-byte compatible with Android core/Crypto.kt & Models.kt:
 * - Key derivation: PBKDF2-HMAC-SHA256 (310,000 iterations, 256-bit key)
 * - Capability UID derivation: PBKDF2-HMAC-SHA256(password, "personal-backup-auth-v2:" + usernameId, 310,000)
 *   -> "v_" + SHA-256("uid:" || authSeed)[0..48]
 * - Record cipher: AES-256-GCM (12-byte IV, 128-bit auth tag, AAD = "${id}|${type}|${ts}")
 * - Passphrase verification box: plain = "backup-check-v1", AAD = "check"
 * - Deterministic Record IDs: first 40 hex chars of SHA-256(parts joined with "\u001f")
 */
export const PBKDF2_ITERATIONS = 310_000;
export const CHECK_PLAIN = new TextEncoder().encode('backup-check-v1');
export const CHECK_AAD = new TextEncoder().encode('check');

const encoder = new TextEncoder();
const decoder = new TextDecoder();

export function randomBytes(length: number): Uint8Array {
  const buf = new Uint8Array(length);
  crypto.getRandomValues(buf);
  return buf;
}

export function b64(bytes: Uint8Array): string {
  let binary = '';
  const chunkSize = 0x8000;
  for (let i = 0; i < bytes.length; i += chunkSize) {
    const chunk = bytes.subarray(i, i + chunkSize);
    binary += String.fromCharCode(...chunk);
  }
  return btoa(binary);
}

export function unb64(encoded: string): Uint8Array {
  const binary = atob(encoded);
  const out = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    out[i] = binary.charCodeAt(i);
  }
  return out;
}

function toArrayBuffer(u8: Uint8Array): ArrayBuffer {
  return u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength) as ArrayBuffer;
}

export async function sha256Hex(input: string | Uint8Array): Promise<string> {
  const bytes = typeof input === 'string' ? encoder.encode(input) : input;
  const digest = await crypto.subtle.digest('SHA-256', toArrayBuffer(bytes));
  return Array.from(new Uint8Array(digest))
    .map((b) => b.toString(16).padStart(2, '0'))
    .join('');
}

/**
 * Derives the deterministic 192-bit account capability UID (`v_` + 48 hex chars)
 * from `(usernameId, password)` using 310,000 iterations of PBKDF2-HMAC-SHA256.
 * Identical across `backup-app`, `viewer-app`, and the Web application.
 */
export async function deriveCapabilityUid(
  usernameId: string,
  password: string,
  iterations: number = PBKDF2_ITERATIONS
): Promise<string> {
  const saltBytes = encoder.encode(`personal-backup-auth-v2:${usernameId}`);
  const baseKey = await crypto.subtle.importKey(
    'raw',
    toArrayBuffer(encoder.encode(password)),
    'PBKDF2',
    false,
    ['deriveBits']
  );
  const seedBits = await crypto.subtle.deriveBits(
    {
      name: 'PBKDF2',
      salt: toArrayBuffer(saltBytes),
      iterations,
      hash: 'SHA-256',
    },
    baseKey,
    256
  );
  const seedBytes = new Uint8Array(seedBits);
  const prefix = encoder.encode('uid:');
  const combined = new Uint8Array(prefix.length + seedBytes.length);
  combined.set(prefix, 0);
  combined.set(seedBytes, prefix.length);
  const hex = await sha256Hex(combined);
  seedBytes.fill(0);
  combined.fill(0);
  return `v_${hex.slice(0, 48)}`;
}

export async function makeRecordId(
  deviceId: string,
  type: RecordType,
  ...parts: Array<string | number | null | undefined>
): Promise<string> {
  const joined = [deviceId, type, ...parts.map((p) => (p == null ? '' : String(p)))].join('\u001f');
  const hex = await sha256Hex(joined);
  return hex.slice(0, 40);
}

export async function deriveVaultKey(
  passphrase: string,
  salt: Uint8Array,
  iterations: number = PBKDF2_ITERATIONS
): Promise<CryptoKey> {
  const baseKey = await crypto.subtle.importKey(
    'raw',
    toArrayBuffer(encoder.encode(passphrase)),
    'PBKDF2',
    false,
    ['deriveKey']
  );

  return crypto.subtle.deriveKey(
    {
      name: 'PBKDF2',
      salt: toArrayBuffer(salt),
      iterations,
      hash: 'SHA-256',
    },
    baseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt']
  );
}

export async function encryptBox(
  key: CryptoKey,
  plain: Uint8Array,
  aad: Uint8Array
): Promise<{ iv: Uint8Array; data: Uint8Array }> {
  const iv = randomBytes(12);
  const cipherBuffer = await crypto.subtle.encrypt(
    {
      name: 'AES-GCM',
      iv: toArrayBuffer(iv),
      additionalData: toArrayBuffer(aad),
      tagLength: 128,
    },
    key,
    toArrayBuffer(plain)
  );
  return { iv, data: new Uint8Array(cipherBuffer) };
}

export async function decryptBox(
  key: CryptoKey,
  iv: Uint8Array,
  data: Uint8Array,
  aad: Uint8Array
): Promise<Uint8Array> {
  const plainBuffer = await crypto.subtle.decrypt(
    {
      name: 'AES-GCM',
      iv: toArrayBuffer(iv),
      additionalData: toArrayBuffer(aad),
      tagLength: 128,
    },
    key,
    toArrayBuffer(data)
  );
  return new Uint8Array(plainBuffer);
}

function recordAad(id: string, type: string, ts: number): Uint8Array {
  return encoder.encode(`${id}|${type}|${ts}`);
}

export async function encryptRecord(key: CryptoKey, record: PlainRecord): Promise<EncRecord> {
  const jsonBytes = encoder.encode(JSON.stringify(record.fields));
  const aad = recordAad(record.id, record.type, record.ts);
  const box = await encryptBox(key, jsonBytes, aad);
  return {
    id: record.id,
    deviceId: record.deviceId,
    type: record.type,
    ts: record.ts,
    iv: b64(box.iv),
    data: b64(box.data),
  };
}

export async function decryptRecord(
  key: CryptoKey,
  deviceId: string,
  enc: EncRecord
): Promise<PlainRecord> {
  const aad = recordAad(enc.id, enc.type, enc.ts);
  const plainBytes = await decryptBox(key, unb64(enc.iv), unb64(enc.data), aad);
  const parsed = JSON.parse(decoder.decode(plainBytes)) as RecordFields;
  return {
    id: enc.id,
    deviceId,
    type: enc.type,
    ts: enc.ts,
    fields: parsed,
  };
}

/**
 * Browser KeyVault (IndexedDB storage of non-extractable CryptoKey)
 * Mirrors Android Keystore KeyVault.kt: stores non-extractable CryptoKey locally
 * so the user does not have to re-type the password on page reload unless they sign out.
 */
const VAULT_DB_NAME = 'cal1_key_vault_v1';
const VAULT_STORE = 'keys';
const memoryKeyFallback = new Map<string, CryptoKey>();

function openVaultDb(): Promise<IDBDatabase> {
  return new Promise((resolve, reject) => {
    if (typeof indexedDB === 'undefined') {
      reject(new Error('IndexedDB unavailable'));
      return;
    }
    const req = indexedDB.open(VAULT_DB_NAME, 1);
    req.onupgradeneeded = () => {
      if (!req.result.objectStoreNames.contains(VAULT_STORE)) {
        req.result.createObjectStore(VAULT_STORE);
      }
    };
    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error);
  });
}

export const KeyVault = {
  async save(uid: string, key: CryptoKey): Promise<void> {
    memoryKeyFallback.set(uid, key);
    try {
      const db = await openVaultDb();
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(VAULT_STORE, 'readwrite');
        tx.objectStore(VAULT_STORE).put(key, uid);
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
      db.close();
    } catch {
      // Kept in memoryKeyFallback for current runtime if IndexedDB is unavailable
    }
  },

  async load(uid: string): Promise<CryptoKey | null> {
    const inMem = memoryKeyFallback.get(uid);
    if (inMem) return inMem;
    try {
      const db = await openVaultDb();
      const key = await new Promise<CryptoKey | null>((resolve, reject) => {
        const tx = db.transaction(VAULT_STORE, 'readonly');
        const req = tx.objectStore(VAULT_STORE).get(uid);
        req.onsuccess = () => resolve((req.result as CryptoKey | undefined) ?? null);
        req.onerror = () => reject(req.error);
      });
      db.close();
      if (key) memoryKeyFallback.set(uid, key);
      return key;
    } catch {
      return null;
    }
  },

  async clear(uid?: string): Promise<void> {
    if (uid) {
      memoryKeyFallback.delete(uid);
    } else {
      memoryKeyFallback.clear();
    }
    try {
      const db = await openVaultDb();
      await new Promise<void>((resolve, reject) => {
        const tx = db.transaction(VAULT_STORE, 'readwrite');
        const store = tx.objectStore(VAULT_STORE);
        if (uid) {
          store.delete(uid);
        } else {
          store.clear();
        }
        tx.oncomplete = () => resolve();
        tx.onerror = () => reject(tx.error);
      });
      db.close();
    } catch {
      // Ignore cleanup errors
    }
  },
};
