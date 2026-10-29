import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
  RulesTestEnvironment,
} from '@firebase/rules-unit-testing';
import {
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
  deleteDoc,
  collection,
  query,
  where,
  writeBatch,
  serverTimestamp,
  Timestamp,
} from 'firebase/firestore';
import crypto from 'node:crypto';
import fs from 'node:fs';

function computeUsernameId(username: string): string {
  return crypto
    .createHash('sha256')
    .update(`uname:${username.trim().toLowerCase()}`)
    .digest('hex')
    .slice(0, 40);
}

function deriveCapabilityUidNode(usernameId: string, password: string): string {
  const seed = crypto.pbkdf2Sync(
    Buffer.from(password, 'utf8'),
    Buffer.from(`personal-backup-auth-v2:${usernameId}`, 'utf8'),
    2000,
    32,
    'sha256'
  );
  const hex = crypto
    .createHash('sha256')
    .update(Buffer.concat([Buffer.from('uid:', 'utf8'), seed]))
    .digest('hex');
  return `v_${hex.slice(0, 48)}`;
}

/**
 * Adversarial Security Rules Verification Suite ("Dirty Dozen" Payloads + Capability Accounts)
 * Verifies that all 12 attack vectors defined in security_spec.md return PERMISSION_DENIED.
 */
export async function runRulesSecuritySuite(): Promise<void> {
  const testEnv: RulesTestEnvironment = await initializeTestEnvironment({
    projectId: 'demo-cal1-security-test',
    firestore: {
      rules: fs.readFileSync('firestore.rules', 'utf8'),
    },
  });

  try {
    const samiCtx = testEnv.authenticatedContext('sami_uid');
    const malloryCtx = testEnv.authenticatedContext('mallory_uid');
    const anonCtx = testEnv.unauthenticatedContext();

    const samiDb = samiCtx.firestore();
    const malloryDb = malloryCtx.firestore();
    const anonDb = anonCtx.firestore();

    const samiUnameId = computeUsernameId('sami123');

    // Baseline valid registration & backup setup for sami123 (Custom Auth)
    await assertSucceeds(
      setDoc(doc(samiDb, `usernames/${samiUnameId}`), {
        uid: 'sami_uid',
        usernameLower: 'sami123',
        createdAt: serverTimestamp(),
      })
    );

    await assertSucceeds(
      setDoc(doc(samiDb, 'users/sami_uid'), {
        uid: 'sami_uid',
        username: 'sami123',
        usernameLower: 'sami123',
        usernameId: samiUnameId,
        createdAt: serverTimestamp(),
      })
    );

    await assertSucceeds(
      setDoc(doc(samiDb, 'users/sami_uid/meta/crypto'), {
        uid: 'sami_uid',
        salt: 'AAAAAAAAAAAAAAAAAAAAAA==',
        iter: 310000,
        checkIv: 'BBBBBBBBBBBBBBBB',
        check: 'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC',
        v: 1,
        createdAt: serverTimestamp(),
      })
    );

    await assertSucceeds(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_1'), {
        ownerId: 'sami_uid',
        name: 'Pixel 8',
        model: 'Android 14',
        enabled: ['call', 'sms', 'clip'],
        lastSyncAt: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    await assertSucceeds(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_1/records/rec_1'), {
        ownerId: 'sami_uid',
        deviceId: 'dev_1',
        type: 'call',
        ts: 1700000000000,
        iv: 'BBBBBBBBBBBBBBBB',
        data: 'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC',
        v: 1,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // Baseline valid Direct Cloud Capability Account (zero-setup username + password)
    const nourUnameId = computeUsernameId('nour2026');
    const nourUid = deriveCapabilityUidNode(nourUnameId, 'MyBackup2026!');
    const regBatch = writeBatch(anonDb);
    regBatch.set(doc(anonDb, `usernames/${nourUnameId}`), {
      uid: nourUid,
      usernameLower: 'nour2026',
      createdAt: serverTimestamp(),
    });
    regBatch.set(doc(anonDb, `users/${nourUid}`), {
      uid: nourUid,
      username: 'nour2026',
      usernameLower: 'nour2026',
      usernameId: nourUnameId,
      createdAt: serverTimestamp(),
    });
    regBatch.set(doc(anonDb, `users/${nourUid}/meta/crypto`), {
      uid: nourUid,
      salt: 'AAAAAAAAAAAAAAAAAAAAAA==',
      iter: 310000,
      checkIv: 'BBBBBBBBBBBBBBBB',
      check: 'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC',
      v: 1,
      createdAt: serverTimestamp(),
    });
    await assertSucceeds(regBatch.commit());

    // Duplicate capability registration with same username (different password) MUST fail
    const attackerUid = deriveCapabilityUidNode(nourUnameId, 'OtherPass999!');
    const dupBatch = writeBatch(anonDb);
    dupBatch.set(doc(anonDb, `usernames/${nourUnameId}`), {
      uid: attackerUid,
      usernameLower: 'nour2026',
      createdAt: serverTimestamp(),
    });
    dupBatch.set(doc(anonDb, `users/${attackerUid}`), {
      uid: attackerUid,
      username: 'nour2026',
      usernameLower: 'nour2026',
      usernameId: nourUnameId,
      createdAt: serverTimestamp(),
    });
    await assertFails(dupBatch.commit());

    // Standalone deletion of /usernames/{nourUnameId} without knowing secret nourUid MUST fail
    await assertFails(deleteDoc(doc(anonDb, `usernames/${nourUnameId}`)));

    // 1. Client Read/Write of Password Records (/authCredentials)
    await assertFails(getDoc(doc(samiDb, `authCredentials/${samiUnameId}`)));
    await assertFails(
      setDoc(doc(samiDb, `authCredentials/${samiUnameId}`), {
        uid: 'sami_uid',
        passwordHash: 'deadbeef',
      })
    );

    // 2. Client Tampering with Brute-Force Rate Limits (/authRateLimits)
    await assertFails(getDoc(doc(anonDb, 'authRateLimits/ip_1_2_3_4')));
    await assertFails(deleteDoc(doc(samiDb, 'authRateLimits/ip_1_2_3_4')));

    // 3. Duplicate Username Hijack & Enumeration (/usernames/{usernameId})
    await assertFails(
      setDoc(doc(malloryDb, `usernames/${samiUnameId}`), {
        uid: 'mallory_uid',
        usernameLower: 'sami123',
        createdAt: serverTimestamp(),
      })
    );
    await assertFails(getDoc(doc(malloryDb, `usernames/${samiUnameId}`)));

    // 4. Cross-Tenant Identity Spoofing (ownerId mismatch)
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_spoof'), {
        ownerId: 'mallory_uid',
        name: 'Spoofed',
        model: 'Web',
        enabled: ['clip'],
        lastSyncAt: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // 5. Shadow Field Injection on Create
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_shadow'), {
        ownerId: 'sami_uid',
        name: 'Shadow Device',
        model: 'Web',
        enabled: ['clip'],
        lastSyncAt: 0,
        isAdmin: true,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // 6. Crypto Root Overwrite (meta/crypto update forbidden)
    await assertFails(
      updateDoc(doc(samiDb, 'users/sami_uid/meta/crypto'), {
        salt: 'ZZZZZZZZZZZZZZZZZZZZZZ==',
      })
    );

    // 7. Orphaned Record Write (Missing Parent Device)
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/ghost_device/records/rec_1'), {
        ownerId: 'sami_uid',
        deviceId: 'ghost_device',
        type: 'sms',
        ts: 1700000000000,
        iv: 'BBBBBBBBBBBBBBBB',
        data: 'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC',
        v: 1,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // 8. ID Poisoning Attack (Invalid Path Variable)
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/bad$id!@#'), {
        ownerId: 'sami_uid',
        name: 'Bad ID',
        model: 'Web',
        enabled: ['clip'],
        lastSyncAt: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // 9. Denial-of-Wallet Oversized Ciphertext (Value Poisoning)
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_1/records/rec_big'), {
        ownerId: 'sami_uid',
        deviceId: 'dev_1',
        type: 'clip',
        ts: 1700000000000,
        iv: 'BBBBBBBBBBBBBBBB',
        data: 'A'.repeat(600000),
        v: 1,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // 10. Unbounded / Invalid Array Element Injection
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_bad_arr'), {
        ownerId: 'sami_uid',
        name: 'Bad Array',
        model: 'Web',
        enabled: ['call', 'sms', 'clip', 'contacts'],
        lastSyncAt: 0,
        createdAt: serverTimestamp(),
        updatedAt: serverTimestamp(),
      })
    );

    // 11. Action-Gap Immutable Field Mutation & Client Timestamp Forgery
    await assertFails(
      updateDoc(doc(samiDb, 'users/sami_uid/devices/dev_1/records/rec_1'), {
        type: 'sms',
        iv: 'BBBBBBBBBBBBBBBB',
        data: 'CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC',
        updatedAt: serverTimestamp(),
      })
    );
    await assertFails(
      setDoc(doc(samiDb, 'users/sami_uid/devices/dev_forged_ts'), {
        ownerId: 'sami_uid',
        name: 'Forged TS',
        model: 'Web',
        enabled: ['clip'],
        lastSyncAt: 0,
        createdAt: Timestamp.fromMillis(1577836800000),
        updatedAt: serverTimestamp(),
      })
    );

    // 12. Cross-Account Backup Scraping (get & list)
    await assertFails(getDocs(collection(anonDb, 'users')));
    await assertFails(getDoc(doc(malloryDb, 'users/sami_uid')));
    await assertFails(getDoc(doc(malloryDb, `users/${nourUid}`)));
    await assertFails(getDoc(doc(malloryDb, 'users/sami_uid/meta/crypto')));
    await assertFails(
      getDocs(
        query(collection(malloryDb, 'users/sami_uid/devices'), where('ownerId', '==', 'sami_uid'))
      )
    );
    // Unfiltered query scraping without ownerId filter
    await assertFails(getDocs(collection(samiDb, 'users/sami_uid/devices')));
  } finally {
    await testEnv.cleanup();
  }
}
