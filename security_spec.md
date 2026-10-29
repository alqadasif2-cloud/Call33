# Security Specification (`security_spec.md`)

## 1. Data Invariants

1. **Zero-Trust Tenant Isolation (`uid` & `ownerId`)**:
   - Every user backup path is strictly scoped under `/users/{uid}/...` protected by `isAuthorizedOwner(uid)`:
     - Either an authenticated Firebase Auth / Custom Auth token where `request.auth != null && request.auth.uid == uid`, OR
     - A 192-bit PBKDF2-HMAC-SHA256 cryptographic capability identifier (`^v_[a-f0-9]{48}$`, 50 chars) derived deterministically on-device from `(usernameId, password)` over 310,000 iterations (`!isSignedIn() && isVaultCapabilityId(uid)`), with `allow list: if false` on `/users` and `/usernames` preventing enumeration or guessing (`2^-192`).
   - All mutable or listable documents (`Device`, `EncRecord`) embed `ownerId == uid` (and `UserProfile`, `CryptoMeta` embed `uid == uid`), enforced at creation and locked as immutable on update.
2. **Server-Only Password & Rate-Limit Vault (`/authCredentials` & `/authRateLimits`)**:
   - `/authCredentials/{usernameId}` and `/authRateLimits/{limitId}` enforce `allow read, write: if false;`. No client APK or browser can ever read, list, create, update, or delete server credential records or rate-limit counters.
3. **Global Atomic Username Uniqueness & Anti-Enumeration (`/usernames/{usernameId}` & `/users/{uid}`)**:
   - `/usernames/{usernameId}` (`usernameId = sha256("uname:" + usernameLower).slice(0, 40)`) allows `create` only once when the document does not yet exist (`allow update: if false;`), atomically preventing duplicate usernames across all apps (`backup-app`, `viewer-app`, and Web).
   - For capability accounts (`v_<48-hex>`), `create` on `/usernames/{usernameId}` and `/users/{uid}` is atomically coupled via `existsAfter` and `getAfter` so neither can be created without the other in the same atomic batch.
   - `/usernames/{usernameId}` forbids `get` and `list` (`allow get, list: if false;`); `delete` is allowed solely by the authenticated `request.auth.uid` owner OR atomically when deleting the secret `/users/$(existing().uid)` profile in the same batch (`exists` + `!existsAfter`), preventing third-party username release/hijack.
4. **Immutable Cryptographic Root (`/users/{uid}/meta/crypto`)**:
   - Only `metaId == 'crypto'` may exist in `/users/{uid}/meta/{metaId}`.
   - `CryptoMeta` can only be created once (`allow update: if false;`) and deleted only by `isAuthorizedOwner(uid)`. `list` is disallowed.
5. **Master Gate Relational Integrity (`/users/{uid}/devices/{deviceId}/records/{recId}`)**:
   - An encrypted record (`EncRecord`) cannot be created, read (`get`), updated, or deleted unless its parent `/users/{uid}/devices/{deviceId}` document exists AND `get(...).data.ownerId == uid`.
   - `allow list` on `devices` and `records` explicitly checks `resource.data.ownerId == uid` (and `resource.data.deviceId == deviceId` on `records`) with zero `get()` or `exists()` calls inside `list` blocks.

---

## 2. The "Dirty Dozen" Adversarial Payloads

1. **Payload 1 — Client Read/Write of Password Records (`/authCredentials`)**:
   Client attempts `get` or `set` on `/authCredentials/<usernameId>` to read or overwrite password hashes.
2. **Payload 2 — Client Tampering with Brute-Force Rate Limits (`/authRateLimits`)**:
   Attacker attempts `delete` or `set` on `/authRateLimits/<limitId>` to reset lockout counters.
3. **Payload 3 — Duplicate Username Hijack & Standalone Username Deletion (`/usernames/{usernameId}`)**:
   Attacker attempts `setDoc` on `/usernames/<sami_username_id>` already claimed by `sami`, attempts `getDoc` to enumerate usernames, or attempts `deleteDoc` on `/usernames/<sami_username_id>` without knowing `sami`'s 192-bit capability `uid`.
4. **Payload 4 — Cross-Tenant Identity Spoofing (`ownerId` Mismatch)**:
   User writes `/users/{uid}/devices/dev_1` with `"ownerId"` set to a different UID.
5. **Payload 5 — Shadow Field Injection on Create**:
   User creates `/users/{uid}/devices/dev_1` or `/users/{uid}` with all required keys plus `"isAdmin": true`.
6. **Payload 6 — Crypto Root Overwrite (`meta/crypto` Update)**:
   User attempts to `update` `/users/{uid}/meta/crypto` to swap out the PBKDF2 `salt` after creation.
7. **Payload 7 — Orphaned Record Write (Missing Parent Device)**:
   User writes `/users/{uid}/devices/ghost_device/records/rec_1` when `/users/{uid}/devices/ghost_device` does not exist.
8. **Payload 8 — ID Poisoning Attack (Invalid Path Variable)**:
   User attempts to create `/users/{uid}/devices/bad$id!@#` containing characters outside `^[a-zA-Z0-9_\-]+$`.
9. **Payload 9 — Denial-of-Wallet Oversized Ciphertext (`Value Poisoning`)**:
   User writes `/users/{uid}/devices/dev_1/records/rec_1` with a 600,000-char `data` string exceeding `maxLength: 500000`.
10. **Payload 10 — Unbounded / Invalid Array Element Injection**:
    User writes `/users/{uid}/devices/dev_1` with `"enabled": ["call", "sms", "clip", "contacts"]` or `"enabled": ["contacts"]`.
11. **Payload 11 — Action-Gap Immutable Field Mutation & Timestamp Forgery**:
    User updates `/users/{uid}/devices/dev_1/records/rec_1` attempting to mutate `"type"` or `"ts"`, or creates a device with a forged past client `Timestamp`.
12. **Payload 12 — Cross-Account Backup Scraping (`get` & `list`)**:
    User `mallory_uid` or unauthenticated attacker attempts to `list` `/users`, `get` `/users/sami_uid`, or `list` `/users/sami_uid/devices` without `ownerId` match.
