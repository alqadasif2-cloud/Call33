import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  AlertTriangle,
  ClipboardPaste,
  CloudUpload,
  FileUp,
  Pause,
  Play,
  Plus,
  RefreshCw,
  Trash2,
} from 'lucide-react';
import { CloudRepo } from '../core/cloudRepo';
import { encryptRecord } from '../core/crypto';
import { TimeFmt } from '../core/csvAndTime';
import { friendlyErrorMessageAr } from '../core/firebase';
import { Importer, isValidPlainRecord, RecBuilder } from '../core/importer';
import { clipResultMessageAr, recordTypeLongAr } from '../core/labels';
import {
  LocalDevicePrefs,
  LocalPrefsStore,
  LocalQueueDb,
  syncStateLabelAr,
} from '../core/localQueue';
import { looksSensitive } from '../core/sensitive';
import {
  ClipResult,
  LocalQueuedRecord,
  PlainRecord,
  RecordType,
  SyncErrorDiagnostic,
  SyncStateCode,
} from '../core/types';

interface BackupSourceViewProps {
  uid: string;
  vaultKey: CryptoKey;
  prefs: LocalDevicePrefs;
  onUpdatePrefs: (next: LocalDevicePrefs) => void;
  onCloudChanged: () => void;
}

function classifySyncErrorWeb(err: unknown): { summary: string; remediation: string } {
  const friendly = friendlyErrorMessageAr(err);
  const raw = err instanceof Error ? err.message.toLowerCase() : String(err).toLowerCase();
  if (!navigator.onLine || raw.includes('offline') || raw.includes('unavailable') || raw.includes('network')) {
    return {
      summary: 'تعذّر الوصول إلى خادم السحابة بسبب انقطاع الإنترنت أو ضعفه',
      remediation:
        'جميع السجلات محفوظة محليًا في قائمة الانتظار، وسيُعاد رفع السجلات غير المتزامنة تلقائيًا فور عودة الاتصال.',
    };
  }
  if (raw.includes('permission-denied') || raw.includes('insufficient permissions')) {
    return {
      summary: 'رفضت قواعد أمان Firebase حفظ الدفعة',
      remediation:
        'سجّل الخروج ثم أعد تسجيل الدخول لتجديد جلسة المصادقة، ثم اضغط «إعادة محاولة السجلات الفاشلة فقط».',
    };
  }
  return {
    summary: friendly,
    remediation: 'تحقق من استقرار الشبكة أو اضغط «إعادة محاولة السجلات الفاشلة فقط» لإعادة إرسالها دون تكرار البقية.',
  };
}

function summarizeLocalRow(row: LocalQueuedRecord | undefined): string {
  if (!row) return 'لم يُلتقط أي سجل بعد';
  try {
    const f = JSON.parse(row.json) as Record<string, unknown>;
    if (row.type === RecordType.CALL) {
      const num = String(f.number || 'رقم مخفي');
      const name = f.name ? `${String(f.name)} (${num})` : num;
      const dur = Number(f.durationSec || 0);
      return `${TimeFmt.human(row.ts)} — ${name} • ${dur}ث (${syncStateLabelAr(row.syncState)})`;
    }
    if (row.type === RecordType.SMS) {
      const addr = String(f.address || 'غير معروف');
      const body = String(f.body || '').replace(/\s+/g, ' ').slice(0, 45);
      return `${TimeFmt.human(row.ts)} — ${addr}: ${body} (${syncStateLabelAr(row.syncState)})`;
    }
    const text = String(f.text || '').replace(/\s+/g, ' ').slice(0, 55);
    return `${TimeFmt.human(row.ts)} — ${text} (${syncStateLabelAr(row.syncState)})`;
  } catch {
    return `${TimeFmt.human(row.ts)} (${syncStateLabelAr(row.syncState)})`;
  }
}

function syncBadgeClasses(state: SyncStateCode): string {
  switch (state) {
    case 1:
      return 'bg-emerald-50 text-emerald-800 border-emerald-200';
    case 2:
      return 'bg-red-50 text-red-700 border-red-200';
    case 3:
      return 'bg-slate-100 text-slate-700 border-slate-200';
    default:
      return 'bg-amber-50 text-amber-800 border-amber-200';
  }
}

export const BackupSourceView: React.FC<BackupSourceViewProps> = ({
  uid,
  vaultKey,
  prefs,
  onUpdatePrefs,
  onCloudChanged,
}) => {
  const [localRows, setLocalRows] = useState<LocalQueuedRecord[]>([]);
  const [online, setOnline] = useState<boolean>(navigator.onLine);
  const [syncing, setSyncing] = useState(false);
  const [statusNotice, setStatusNotice] = useState<string | null>(null);
  const [errorNotice, setErrorNotice] = useState<string | null>(null);
  const [editingName, setEditingName] = useState(prefs.deviceName);
  const [clipInput, setClipInput] = useState('');

  const [manualKind, setManualKind] = useState<'call' | 'sms'>('call');
  const [callNumber, setCallNumber] = useState('');
  const [callName, setCallName] = useState('');
  const [callType, setCallType] = useState('incoming');
  const [callDuration, setCallDuration] = useState('60');

  const [smsAddress, setSmsAddress] = useState('');
  const [smsType, setSmsType] = useState('inbox');
  const [smsBody, setSmsBody] = useState('');

  const callsFileRef = useRef<HTMLInputElement | null>(null);
  const smsFileRef = useRef<HTMLInputElement | null>(null);
  const lastAutoClipRef = useRef<string>('');
  const retryTimerRef = useRef<number | null>(null);

  const refreshLocalStats = useCallback(async () => {
    const rows = await LocalQueueDb.listAll(uid);
    setLocalRows(rows);
  }, [uid]);

  useEffect(() => {
    void refreshLocalStats();
  }, [refreshLocalStats]);

  useEffect(() => {
    const onUp = () => setOnline(true);
    const onDown = () => setOnline(false);
    window.addEventListener('online', onUp);
    window.addEventListener('offline', onDown);
    return () => {
      window.removeEventListener('online', onUp);
      window.removeEventListener('offline', onDown);
      if (retryTimerRef.current !== null) {
        window.clearTimeout(retryTimerRef.current);
      }
    };
  }, []);

  /**
   * Encrypts pending/failed local records with AES-256-GCM and commits batches to Cloud Firestore.
   * - Never marks a record synced before Firestore batch.commit() confirms durability.
   * - Isolates partial failures so valid records are marked synced and ONLY failed records retry.
   * - Never updates lastSyncAt when 0 records were uploaded.
   */
  const runSyncNow = useCallback(
    async (overridePrefs?: LocalDevicePrefs, retryDelayMs = 15_000) => {
      const activePrefs = overridePrefs ?? prefs;
      if (activePrefs.paused) {
        setStatusNotice('النسخ الاحتياطي متوقف مؤقتًا. السجلات الجديدة تُحفظ محليًا فقط حتى الاستئناف.');
        return;
      }
      if (!navigator.onLine) {
        setErrorNotice('لا يوجد اتصال بالإنترنت حاليًا. السجلات محفوظة محليًا في قائمة الانتظار وستُرفع تلقائيًا فور عودة الاتصال.');
        return;
      }

      setSyncing(true);
      setErrorNotice(null);

      try {
        await LocalQueueDb.promoteLocalToPending(uid);
        const firstPending = await LocalQueueDb.pendingBatch(uid, 100);
        if (firstPending.length === 0) {
          if (activePrefs.lastSyncError) {
            const cleared = { ...activePrefs, lastSyncError: null };
            LocalPrefsStore.save(uid, cleared);
            onUpdatePrefs(cleared);
          }
          await refreshLocalStats();
          setStatusNotice('لا توجد سجلات معلّقة بانتظار الرفع.');
          return;
        }

        await CloudRepo.registerOrUpdateDevice(
          activePrefs.deviceId,
          activePrefs.deviceName,
          activePrefs.model,
          activePrefs.enabled
        );

        let uploadedCount = 0;
        let failedCount = 0;
        let latestDiag: SyncErrorDiagnostic | null = null;
        const failedSeen = new Set<string>();

        while (navigator.onLine) {
          const candidates = (await LocalQueueDb.pendingBatch(uid, 150))
            .filter((r) => !failedSeen.has(r.id))
            .slice(0, 100);
          if (candidates.length === 0) break;

          const encryptedPairs: { row: LocalQueuedRecord; enc: Awaited<ReturnType<typeof encryptRecord>> }[] = [];
          for (const row of candidates) {
            try {
              const enc = await encryptRecord(vaultKey, LocalQueueDb.toPlainRecord(row));
              encryptedPairs.push({ row, enc });
            } catch {
              const msg = 'سجل محلي غير صالح للتشفير';
              await LocalQueueDb.markFailed(uid, [row.id], msg);
              failedSeen.add(row.id);
              failedCount++;
              latestDiag = {
                summary: msg,
                remediation: 'يمكنك حذف السجل التالف وإعادة استيراده.',
                occurredAt: Date.now(),
              };
            }
          }
          if (encryptedPairs.length === 0) continue;

          try {
            await CloudRepo.uploadBatch(
              activePrefs.deviceId,
              encryptedPairs.map((p) => p.enc)
            );
            await LocalQueueDb.markSynced(
              uid,
              encryptedPairs.map((p) => p.row.id)
            );
            uploadedCount += encryptedPairs.length;
          } catch (batchErr) {
            if (!navigator.onLine) break;
            if (encryptedPairs.length === 1) {
              const { summary, remediation } = classifySyncErrorWeb(batchErr);
              await LocalQueueDb.markFailed(uid, [encryptedPairs[0].row.id], summary);
              failedSeen.add(encryptedPairs[0].row.id);
              failedCount++;
              latestDiag = { summary, remediation, occurredAt: Date.now() };
            } else {
              for (const item of encryptedPairs) {
                if (!navigator.onLine) break;
                try {
                  await CloudRepo.uploadBatch(activePrefs.deviceId, [item.enc]);
                  await LocalQueueDb.markSynced(uid, [item.row.id]);
                  uploadedCount++;
                } catch (itemErr) {
                  const { summary, remediation } = classifySyncErrorWeb(itemErr);
                  await LocalQueueDb.markFailed(uid, [item.row.id], summary);
                  failedSeen.add(item.row.id);
                  failedCount++;
                  latestDiag = { summary, remediation, occurredAt: Date.now() };
                }
              }
            }
          }
        }

        let nextLastSyncAt = activePrefs.lastSyncAt;
        if (uploadedCount > 0) {
          const now = Date.now();
          await CloudRepo.markDeviceSynced(activePrefs.deviceId, now);
          nextLastSyncAt = now;
          onCloudChanged();
        }

        const updatedPrefs: LocalDevicePrefs = {
          ...activePrefs,
          lastSyncAt: nextLastSyncAt,
          lastSyncError: failedCount > 0 ? latestDiag : null,
        };
        LocalPrefsStore.save(uid, updatedPrefs);
        onUpdatePrefs(updatedPrefs);
        await refreshLocalStats();

        if (failedCount === 0 && uploadedCount > 0) {
          setStatusNotice(
            `تم تشفير ورفع ${uploadedCount} سجل فعلي إلى Firebase وتأكيد حفظها.`
          );
        } else if (failedCount > 0) {
          setErrorNotice(
            uploadedCount > 0
              ? `تم رفع ${uploadedCount} سجل بنجاح، وفشل رفع ${failedCount} سجل (ستُعاد محاولة السجلات الفاشلة فقط تلقائيًا).`
              : `فشل رفع ${failedCount} سجل. ستُعاد المحاولة تلقائيًا بتأخير متزايد.`
          );
          if (retryTimerRef.current !== null) window.clearTimeout(retryTimerRef.current);
          const nextDelay = Math.min(retryDelayMs * 2, 120_000);
          retryTimerRef.current = window.setTimeout(() => {
            if (navigator.onLine && !updatedPrefs.paused) {
              void runSyncNow(updatedPrefs, nextDelay);
            }
          }, retryDelayMs);
        }
      } catch (err) {
        const { summary, remediation } = classifySyncErrorWeb(err);
        const diag: SyncErrorDiagnostic = { summary, remediation, occurredAt: Date.now() };
        const updatedPrefs: LocalDevicePrefs = { ...activePrefs, lastSyncError: diag };
        LocalPrefsStore.save(uid, updatedPrefs);
        onUpdatePrefs(updatedPrefs);
        await refreshLocalStats();
        setErrorNotice(summary);
      } finally {
        setSyncing(false);
      }
    },
    [prefs, uid, vaultKey, onUpdatePrefs, refreshLocalStats, onCloudChanged]
  );

  // استئناف المزامنة تلقائيًا فور عودة الاتصال بالإنترنت
  useEffect(() => {
    if (online && !prefs.paused && !syncing) {
      const hasWaiting = localRows.some((r) => r.syncState === 0 || r.syncState === 2 || r.syncState === 3);
      if (hasWaiting) {
        void runSyncNow();
      }
    }
  }, [online]);

  const saveClipboardText = useCallback(
    async (rawText: string, silentIfDuplicate = false) => {
      if (!prefs.enabled.includes(RecordType.CLIP)) {
        if (!silentIfDuplicate) setStatusNotice(clipResultMessageAr(ClipResult.DISABLED));
        return;
      }
      const trimmed = rawText.trim();
      if (!trimmed) {
        if (!silentIfDuplicate) setStatusNotice(clipResultMessageAr(ClipResult.EMPTY));
        return;
      }
      if (trimmed.length > 100_000) {
        if (!silentIfDuplicate) setStatusNotice(clipResultMessageAr(ClipResult.TOO_LONG));
        return;
      }
      if (looksSensitive(trimmed)) {
        if (!silentIfDuplicate) setErrorNotice(clipResultMessageAr(ClipResult.SENSITIVE));
        return;
      }

      const initialState: SyncStateCode = prefs.paused ? 3 : 0;
      const rec = await RecBuilder.clip(prefs.deviceId, trimmed, Date.now());
      const inserted = await LocalQueueDb.insertIgnore(uid, [rec], initialState);
      lastAutoClipRef.current = trimmed;

      const nextPrefs = { ...prefs, lastScanAt: Date.now() };
      LocalPrefsStore.save(uid, nextPrefs);
      onUpdatePrefs(nextPrefs);
      await refreshLocalStats();

      if (inserted === 0) {
        if (!silentIfDuplicate) setStatusNotice(clipResultMessageAr(ClipResult.DUPLICATE));
        return;
      }

      setClipInput('');
      setErrorNotice(null);
      setStatusNotice(
        prefs.paused
          ? 'تم حفظ النص محليًا (المزامنة متوقفة مؤقتًا).'
          : navigator.onLine
          ? 'تم التقاط نص الحافظة محليًا وجدولة رفعه التلقائي إلى Firebase.'
          : 'تم حفظ النص محليًا في قائمة الانتظار وسيُرفع تلقائيًا فور عودة الإنترنت.'
      );
      if (!nextPrefs.paused && navigator.onLine) {
        await runSyncNow(nextPrefs);
      }
    },
    [prefs, uid, onUpdatePrefs, refreshLocalStats, runSyncNow]
  );

  // التقاط تلقائي للحافظة عند تركيز النافذة أو النسخ/اللصق عندما تسمح صلاحيات المتصفح والنظام
  useEffect(() => {
    if (!prefs.autoClipCapture || !prefs.enabled.includes(RecordType.CLIP)) return;

    const tryAutoReadClipboard = async () => {
      try {
        if (!navigator.clipboard?.readText) return;
        const text = await navigator.clipboard.readText();
        const trimmed = text.trim();
        if (!trimmed || trimmed === lastAutoClipRef.current) return;
        await saveClipboardText(trimmed, true);
      } catch {
        // المتصفح يمنع قراءة الحافظة بدون تفاعل صريح؛ البدائل متاحة دون أخطاء مزعجة
      }
    };

    const onWindowFocus = () => {
      void tryAutoReadClipboard();
    };
    const onCopyEvent = () => {
      const selected = window.getSelection()?.toString().trim() ?? '';
      if (selected && selected !== lastAutoClipRef.current) {
        void saveClipboardText(selected, true);
      }
    };

    window.addEventListener('focus', onWindowFocus);
    document.addEventListener('copy', onCopyEvent);
    return () => {
      window.removeEventListener('focus', onWindowFocus);
      document.removeEventListener('copy', onCopyEvent);
    };
  }, [prefs.autoClipCapture, prefs.enabled, saveClipboardText]);

  const handleSaveDeviceName = async () => {
    const trimmed = editingName.trim().slice(0, 100);
    if (!trimmed || trimmed === prefs.deviceName) return;
    const next = { ...prefs, deviceName: trimmed };
    LocalPrefsStore.save(uid, next);
    onUpdatePrefs(next);
    try {
      await CloudRepo.registerOrUpdateDevice(next.deviceId, next.deviceName, next.model, next.enabled);
      onCloudChanged();
      setStatusNotice('تم تحديث اسم الجهاز في السحابة.');
    } catch (err) {
      setErrorNotice(friendlyErrorMessageAr(err));
    }
  };

  const handleToggleType = async (type: RecordType) => {
    const nextEnabled = prefs.enabled.includes(type)
      ? prefs.enabled.filter((t) => t !== type)
      : [...prefs.enabled, type];
    const next = { ...prefs, enabled: nextEnabled };
    LocalPrefsStore.save(uid, next);
    onUpdatePrefs(next);
    try {
      await CloudRepo.registerOrUpdateDevice(next.deviceId, next.deviceName, next.model, next.enabled);
      onCloudChanged();
    } catch {
      // Synced on next run
    }
  };

  const handleTogglePause = async () => {
    const next = { ...prefs, paused: !prefs.paused };
    LocalPrefsStore.save(uid, next);
    onUpdatePrefs(next);
    if (!next.paused) {
      await LocalQueueDb.promoteLocalToPending(uid);
      await refreshLocalStats();
      void runSyncNow(next);
    }
  };

  const handleReadSystemClipboard = async () => {
    try {
      const text = await navigator.clipboard.readText();
      await saveClipboardText(text, false);
    } catch {
      setErrorNotice(
        'لم يسمح المتصفح بقراءة الحافظة مباشرةً؛ الصق النص في الحقل أدناه للحفظ الفوري.'
      );
    }
  };

  const handleRetryFailedOnly = async () => {
    const retried = await LocalQueueDb.retryFailedNow(uid);
    await refreshLocalStats();
    if (retried > 0) {
      await runSyncNow();
    }
  };

  const handleDeleteLocalClip = async (row: LocalQueuedRecord) => {
    setErrorNotice(null);
    try {
      if (row.syncState === 1) {
        await CloudRepo.deleteRecord(prefs.deviceId, row.id);
        onCloudChanged();
      }
      await LocalQueueDb.deleteOne(uid, row.id);
      await refreshLocalStats();
      setStatusNotice('حُذف السجل محليًا ومن السحابة.');
    } catch {
      setErrorNotice('تعذّر حذف النسخة السحابية للسجل، لذا أُبقي محليًا لضمان اتساق الحالة.');
    }
  };

  const handleFilesImport = async (fileList: FileList, category: 'calls' | 'sms') => {
    setErrorNotice(null);
    setStatusNotice(null);
    const files = Array.from(fileList);
    if (files.length === 0) return;

    try {
      const allValid: PlainRecord[] = [];
      let totalInvalid = 0;

      for (const file of files) {
        const text = await file.text();
        const parsed = await Importer.parseBackupFile(prefs.deviceId, file.name, text, category);
        const valid = parsed.records.filter((r) => isValidPlainRecord(r));
        allValid.push(...valid);
        totalInvalid += parsed.skipped + (parsed.records.length - valid.length);
      }

      const initialState: SyncStateCode = prefs.paused ? 3 : 0;
      const added = await LocalQueueDb.insertIgnore(uid, allValid, initialState);
      const duplicateCount = allValid.length - added;

      const nextPrefs = { ...prefs, lastScanAt: Date.now() };
      LocalPrefsStore.save(uid, nextPrefs);
      onUpdatePrefs(nextPrefs);
      await refreshLocalStats();

      setStatusNotice(
        `تم استيراد ${added} سجل جديد، وتخطّي ${totalInvalid} سجل غير صالح، و${duplicateCount} سجل مكرر.`
      );

      if (added > 0 && !nextPrefs.paused && navigator.onLine) {
        await runSyncNow(nextPrefs);
      }
    } catch (err) {
      setErrorNotice(
        `فشل الاستيراد: الملف غير مطابق للصيغة المتوقعة (${
          err instanceof Error ? err.message : 'خطأ في القراءة'
        }).`
      );
    }
  };

  const handleManualLogSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErrorNotice(null);
    const now = Date.now();
    const initialState: SyncStateCode = prefs.paused ? 3 : 0;

    if (manualKind === 'call') {
      if (!prefs.enabled.includes(RecordType.CALL)) {
        setErrorNotice('تفعيل «سجل المكالمات» مطلوب أولًا.');
        return;
      }
      const dur = Number.parseInt(callDuration, 10) || 0;
      const rec = await RecBuilder.call(
        prefs.deviceId,
        callNumber,
        now,
        callType,
        dur,
        callName || null
      );
      const added = await LocalQueueDb.insertIgnore(uid, [rec], initialState);
      const nextPrefs = { ...prefs, lastScanAt: now };
      LocalPrefsStore.save(uid, nextPrefs);
      onUpdatePrefs(nextPrefs);
      await refreshLocalStats();
      setCallNumber('');
      setCallName('');
      setStatusNotice(added > 0 ? 'أُضيف سجل المكالمة محليًا.' : 'سجل المكالمة موجود مسبقًا.');
      if (added > 0 && !prefs.paused && navigator.onLine) await runSyncNow(nextPrefs);
    } else {
      if (!prefs.enabled.includes(RecordType.SMS)) {
        setErrorNotice('تفعيل «الرسائل النصية» مطلوب أولًا.');
        return;
      }
      if (!smsAddress.trim() || !smsBody.trim()) {
        setErrorNotice('أدخل رقم المرسل/المستلم ونص الرسالة.');
        return;
      }
      const rec = await RecBuilder.sms(prefs.deviceId, smsAddress, smsBody, now, smsType);
      const added = await LocalQueueDb.insertIgnore(uid, [rec], initialState);
      const nextPrefs = { ...prefs, lastScanAt: now };
      LocalPrefsStore.save(uid, nextPrefs);
      onUpdatePrefs(nextPrefs);
      await refreshLocalStats();
      setSmsAddress('');
      setSmsBody('');
      setStatusNotice(added > 0 ? 'أُضيفت الرسالة النصية محليًا.' : 'الرسالة موجودة مسبقًا.');
      if (added > 0 && !prefs.paused && navigator.onLine) await runSyncNow(nextPrefs);
    }
  };

  const totalLocal = localRows.length;
  const pendingLocal = localRows.filter((r) => r.syncState === 0).length;
  const syncedLocal = localRows.filter((r) => r.syncState === 1).length;
  const failedLocal = localRows.filter((r) => r.syncState === 2).length;
  const localOnlyCount = localRows.filter((r) => r.syncState === 3).length;

  const latestCall = localRows.find((r) => r.type === RecordType.CALL);
  const latestSms = localRows.find((r) => r.type === RecordType.SMS);
  const latestClip = localRows.find((r) => r.type === RecordType.CLIP);
  const recentRecords = localRows.slice(0, 15);

  const syncStatusLabel = prefs.paused
    ? 'متوقف مؤقتًا بأمر المستخدم (حفظ محلي فقط)'
    : syncing
    ? 'جاري التشفير والرفع إلى Firebase...'
    : failedLocal > 0
    ? `فشل رفع ${failedLocal} سجل (مجدول لإعادة المحاولة)`
    : pendingLocal > 0 && !online
    ? `${pendingLocal} سجل بانتظار عودة الإنترنت`
    : pendingLocal > 0
    ? `${pendingLocal} سجل بانتظار الرفع`
    : localOnlyCount > 0
    ? `${localOnlyCount} سجل محفوظ محليًا`
    : syncedLocal > 0
    ? `تمت مزامنة ${syncedLocal} سجل في Firebase`
    : 'جاهز للمراقبة والحفظ (لا توجد سجلات محلية بعد)';

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 pb-5">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">النسخ الاحتياطي والمزامنة التلقائية</h1>
          <p className="mt-1 text-sm text-slate-600">
            تُحفظ السجلات محليًا فور التقاطها، وتُشفَّر بـ AES-256-GCM ثم تُرفع تلقائيًا إلى Firebase مع
            إعادة محاولة السجلات الفاشلة فقط دون تكرار.
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            type="button"
            onClick={() => void runSyncNow()}
            disabled={prefs.paused || syncing}
            className="inline-flex items-center gap-2 rounded-lg bg-[#1F3A5F] px-4 py-2 text-xs font-semibold text-white hover:bg-[#162a47] disabled:opacity-50 whitespace-nowrap shrink-0"
          >
            <CloudUpload className="h-3.5 w-3.5" />
            <span>{syncing ? 'جاري الرفع...' : 'فحص ومزامنة الآن'}</span>
          </button>

          <button
            type="button"
            onClick={() => void handleTogglePause()}
            className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 whitespace-nowrap shrink-0"
          >
            {prefs.paused ? <Play className="h-3.5 w-3.5" /> : <Pause className="h-3.5 w-3.5" />}
            <span>{prefs.paused ? 'استئناف المزامنة' : 'إيقاف مؤقت'}</span>
          </button>
        </div>
      </div>

      {statusNotice && (
        <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
          {statusNotice}
        </div>
      )}

      {errorNotice && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {errorNotice}
        </div>
      )}

      {prefs.lastSyncError && (
        <div className="rounded-xl border border-red-200 bg-red-50/70 p-4">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="space-y-1">
              <div className="flex items-center gap-2 text-sm font-bold text-red-800">
                <AlertTriangle className="h-4 w-4 shrink-0" />
                <span>آخر خطأ مزامنة ({TimeFmt.human(prefs.lastSyncError.occurredAt)})</span>
              </div>
              <p className="text-sm font-semibold text-red-900">{prefs.lastSyncError.summary}</p>
              {prefs.lastSyncError.remediation && (
                <p className="text-xs text-red-800">
                  <strong>كيفية المعالجة:</strong> {prefs.lastSyncError.remediation}
                </p>
              )}
            </div>
            {failedLocal > 0 && !prefs.paused && (
              <button
                type="button"
                onClick={() => void handleRetryFailedOnly()}
                disabled={syncing}
                className="inline-flex items-center gap-1.5 rounded-lg border border-red-300 bg-white px-3.5 py-2 text-xs font-semibold text-red-800 hover:bg-red-50 whitespace-nowrap shrink-0"
              >
                <RefreshCw className="h-3.5 w-3.5" />
                <span>إعادة محاولة السجلات الفاشلة فقط ({failedLocal})</span>
              </button>
            )}
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        <div className="rounded-xl border border-slate-200 bg-white p-6 lg:col-span-7">
          <h2 className="text-base font-bold text-slate-900">الحالة الفعلية والعدادات التفصيلية</h2>
          <div className="mt-4 divide-y divide-slate-100 text-sm">
            <div className="flex items-center justify-between gap-4 py-2.5">
              <span className="text-slate-600">اسم الجهاز</span>
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  maxLength={100}
                  value={editingName}
                  onChange={(e) => setEditingName(e.target.value)}
                  className="w-44 rounded-md border border-slate-200 px-2.5 py-1 text-xs font-semibold text-slate-900 focus:border-[#1F3A5F] focus:outline-none sm:w-56"
                />
                {editingName.trim() !== prefs.deviceName && (
                  <button
                    type="button"
                    onClick={() => void handleSaveDeviceName()}
                    className="rounded-md bg-[#1F3A5F] px-2.5 py-1 text-xs font-semibold text-white hover:bg-[#162a47] whitespace-nowrap shrink-0"
                  >
                    حفظ
                  </button>
                )}
              </div>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">حالة المزامنة الفعلية</span>
              <span className="font-semibold text-slate-900">{syncStatusLabel}</span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">الاتصال بالإنترنت والعمل التلقائي</span>
              <span className={online ? 'font-semibold text-emerald-700' : 'font-semibold text-amber-700'}>
                {online
                  ? 'متصل (رفع تلقائي فوري عند الالتقاط أو عودة الشبكة)'
                  : 'غير متصل (تُحفظ السجلات في قائمة الانتظار المحلية)'}
              </span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">إجمالي السجلات المحلية</span>
              <span className="font-mono font-semibold text-slate-900 tabular-nums">{totalLocal}</span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">تمت مزامنته (مؤكّد في Firebase)</span>
              <span className="font-mono font-semibold text-emerald-700 tabular-nums">{syncedLocal}</span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">بانتظار الرفع</span>
              <span className="font-mono font-semibold text-amber-700 tabular-nums">{pendingLocal}</span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">فشل الرفع (قيد الإعادة التلقائية)</span>
              <span className="font-mono font-semibold text-red-700 tabular-nums">{failedLocal}</span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">محفوظ محليًا فقط</span>
              <span className="font-mono font-semibold text-slate-700 tabular-nums">{localOnlyCount}</span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">آخر رفع فعلي مؤكّد للسحابة</span>
              <span className="font-mono text-xs text-slate-800 tabular-nums">
                {TimeFmt.human(prefs.lastSyncAt)}
              </span>
            </div>
            <div className="flex items-center justify-between py-2.5">
              <span className="text-slate-600">آخر فحص / التقاط للبيانات</span>
              <span className="font-mono text-xs text-slate-800 tabular-nums">
                {TimeFmt.human(prefs.lastScanAt)}
              </span>
            </div>
          </div>
        </div>

        <div className="space-y-6 lg:col-span-5">
          <div className="rounded-xl border border-slate-200 bg-white p-6">
            <h2 className="text-base font-bold text-slate-900">أنواع البيانات المفعّلة</h2>
            <p className="mt-1 text-xs text-slate-500">
              تتحكم المفاتيح أدناه فعليًا في السماح بالتقاط كل نوع وحفظه دون المساس بالسجلات السابقة.
            </p>
            <div className="mt-4 divide-y divide-slate-100">
              {[RecordType.CALL, RecordType.SMS, RecordType.CLIP].map((t) => {
                const checked = prefs.enabled.includes(t);
                return (
                  <label
                    key={t}
                    className="flex cursor-pointer items-center justify-between gap-4 py-3 text-sm"
                  >
                    <span className="font-medium text-slate-800">{recordTypeLongAr(t)}</span>
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => void handleToggleType(t)}
                      className="h-4 w-4 accent-[#1F3A5F]"
                    />
                  </label>
                );
              })}
            </div>
          </div>

          <div className="rounded-xl border border-slate-200 bg-white p-6">
            <h2 className="text-base font-bold text-slate-900">آخر سجل تم التقاطه لكل نوع</h2>
            <div className="mt-3 divide-y divide-slate-100 text-xs">
              <div className="py-2.5">
                <div className="font-bold text-slate-800">سجل المكالمات</div>
                <div className="mt-0.5 text-slate-600">{summarizeLocalRow(latestCall)}</div>
              </div>
              <div className="py-2.5">
                <div className="font-bold text-slate-800">الرسائل النصية</div>
                <div className="mt-0.5 text-slate-600">{summarizeLocalRow(latestSms)}</div>
              </div>
              <div className="py-2.5">
                <div className="font-bold text-slate-800">نصوص الحافظة</div>
                <div className="mt-0.5 text-slate-600">{summarizeLocalRow(latestClip)}</div>
              </div>
            </div>
          </div>
        </div>
      </div>

      {/* Clipboard Auto & Manual Section */}
      <div className="rounded-xl border border-slate-200 bg-white p-6">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="max-w-2xl">
            <h2 className="text-base font-bold text-slate-900">الحافظة التلقائية والبدائل الرسمية</h2>
            <p className="mt-1 text-xs leading-relaxed text-slate-600">
              على أندرويد 8–9 يلتقط تطبيق الهاتف الحافظة تلقائيًا في الواجهة والخلفية؛ وعلى أندرويد 10+
              يلتقطها تلقائيًا فور تركيز النافذة أو بضغطة واحدة عبر قائمة تحديد النص («حفظ في نسخي
              الاحتياطي») أو المشاركة أو إشعار الحفظ السريع، دون خدمات خفية مخالفة لسياسات النظام. وفي
              الويب يُلتقط النص تلقائيًا عند النسخ أو تركيز النافذة متى سمح المتصفح بذلك.
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <label className="inline-flex cursor-pointer items-center gap-2 text-xs font-semibold text-slate-700">
              <input
                type="checkbox"
                checked={prefs.autoClipCapture}
                onChange={() => {
                  const next = { ...prefs, autoClipCapture: !prefs.autoClipCapture };
                  LocalPrefsStore.save(uid, next);
                  onUpdatePrefs(next);
                }}
                className="h-4 w-4 accent-[#1F3A5F]"
              />
              <span>التقاط تلقائي عند النسخ/التركيز</span>
            </label>
            <button
              type="button"
              onClick={() => void handleReadSystemClipboard()}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2 text-xs font-semibold text-slate-800 hover:bg-slate-100 whitespace-nowrap shrink-0"
            >
              <ClipboardPaste className="h-3.5 w-3.5" />
              <span>قراءة الحافظة الآن</span>
            </button>
          </div>
        </div>

        <div className="mt-4 flex flex-col gap-2.5 sm:flex-row">
          <textarea
            rows={2}
            value={clipInput}
            onChange={(e) => setClipInput(e.target.value)}
            onPaste={(e) => {
              const pasted = e.clipboardData?.getData('text');
              if (pasted && prefs.autoClipCapture && prefs.enabled.includes(RecordType.CLIP)) {
                e.preventDefault();
                void saveClipboardText(pasted, false);
              }
            }}
            placeholder="أو الصق نصًا هنا ليُحفظ محليًا ويُرفع مشفّرًا تلقائيًا..."
            className="w-full rounded-lg border border-slate-200 px-3.5 py-2 text-sm text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
          />
          <button
            type="button"
            onClick={() => void saveClipboardText(clipInput, false)}
            disabled={!clipInput.trim()}
            className="rounded-lg bg-[#1F3A5F] px-5 py-2 text-xs font-semibold text-white hover:bg-[#162a47] disabled:opacity-50 whitespace-nowrap shrink-0"
          >
            حفظ النص
          </button>
        </div>

        {recentRecords.length > 0 && (
          <div className="mt-6 border-t border-slate-200 pt-4">
            <h3 className="mb-2 text-xs font-bold text-slate-700">
              أحدث السجلات المحلية وحالة مزامنة كل سجل
            </h3>
            <div className="divide-y divide-slate-100">
              {recentRecords.map((r) => (
                <div key={r.id} className="flex items-start justify-between gap-4 py-2.5">
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-xs font-bold text-slate-800">
                        {recordTypeLongAr(r.type)}
                      </span>
                      <span
                        className={`rounded border px-2 py-0.5 text-[11px] font-semibold ${syncBadgeClasses(
                          r.syncState
                        )}`}
                      >
                        {syncStateLabelAr(r.syncState)}
                      </span>
                    </div>
                    <p className="mt-1 truncate text-xs text-slate-600">{summarizeLocalRow(r)}</p>
                    {r.syncState === 2 && r.lastError && (
                      <p className="mt-0.5 text-xs text-red-600">سبب الفشل: {r.lastError}</p>
                    )}
                  </div>
                  {r.type === RecordType.CLIP && (
                    <button
                      type="button"
                      onClick={() => void handleDeleteLocalClip(r)}
                      className="inline-flex items-center gap-1 rounded px-2 py-1 text-xs font-semibold text-slate-500 hover:bg-red-50 hover:text-red-600 whitespace-nowrap shrink-0"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                      <span>حذف</span>
                    </button>
                  )}
                </div>
              ))}
            </div>
          </div>
        )}
      </div>

      {/* Real File Import + Direct Record Logger */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-12">
        <div className="rounded-xl border border-slate-200 bg-white p-6 lg:col-span-6">
          <h2 className="text-base font-bold text-slate-900">استيراد ملفات الهاتف الفعلية</h2>
          <p className="mt-1.5 text-xs leading-relaxed text-slate-600">
            يدعم الاستيراد المباشر لملفات النسخ الاحتياطي المصدَّرة من تطبيقات الهاتف مثل{' '}
            <strong>SMS Backup &amp; Restore</strong> مع الحفاظ على التوقيت الأصلي ومنع التكرار عبر بصمة
            SHA-256:
          </p>
          <div className="mt-3 space-y-1.5 text-xs text-slate-600">
            <div>
              <strong className="text-slate-900">سجل المكالمات:</strong> ملفات{' '}
              <span className="font-mono">XML</span> (<span className="font-mono">calls-*.xml</span>) أو{' '}
              <span className="font-mono">CSV</span> (<span className="font-mono">number,type,date,duration,name</span>) أو{' '}
              <span className="font-mono">JSON</span>.
            </div>
            <div>
              <strong className="text-slate-900">الرسائل النصية:</strong> ملفات{' '}
              <span className="font-mono">XML</span> (<span className="font-mono">sms-*.xml</span>) أو{' '}
              <span className="font-mono">CSV</span> (<span className="font-mono">address,body,date,type</span>) أو{' '}
              <span className="font-mono">JSON</span>.
            </div>
          </div>

          <input
            ref={callsFileRef}
            type="file"
            multiple
            accept=".xml,.csv,.json,text/xml,application/xml,text/csv,application/json"
            className="hidden"
            onChange={(e) => {
              if (e.target.files && e.target.files.length > 0) {
                void handleFilesImport(e.target.files, 'calls');
              }
              e.target.value = '';
            }}
          />
          <input
            ref={smsFileRef}
            type="file"
            multiple
            accept=".xml,.csv,.json,text/xml,application/xml,text/csv,application/json"
            className="hidden"
            onChange={(e) => {
              if (e.target.files && e.target.files.length > 0) {
                void handleFilesImport(e.target.files, 'sms');
              }
              e.target.value = '';
            }}
          />

          <div className="mt-5 flex flex-wrap items-center gap-3">
            <button
              type="button"
              onClick={() => callsFileRef.current?.click()}
              className="inline-flex items-center gap-2 rounded-lg bg-[#1F3A5F] px-4 py-2.5 text-xs font-semibold text-white hover:bg-[#162a47] whitespace-nowrap shrink-0"
            >
              <FileUp className="h-3.5 w-3.5" />
              <span>استيراد مكالمات (XML / CSV / JSON)</span>
            </button>
            <button
              type="button"
              onClick={() => smsFileRef.current?.click()}
              className="inline-flex items-center gap-2 rounded-lg border border-slate-200 bg-white px-4 py-2.5 text-xs font-semibold text-slate-800 hover:bg-slate-50 whitespace-nowrap shrink-0"
            >
              <FileUp className="h-3.5 w-3.5" />
              <span>استيراد رسائل (XML / CSV / JSON)</span>
            </button>
          </div>
        </div>

        <form
          onSubmit={handleManualLogSubmit}
          className="rounded-xl border border-slate-200 bg-white p-6 lg:col-span-6"
        >
          <div className="flex items-center justify-between gap-4">
            <h2 className="text-base font-bold text-slate-900">تدوين مكالمة أو رسالة مباشرة</h2>
            <div className="inline-flex rounded-lg bg-slate-100 p-1">
              <button
                type="button"
                onClick={() => setManualKind('call')}
                className={`rounded-md px-3 py-1 text-xs font-semibold whitespace-nowrap shrink-0 ${
                  manualKind === 'call'
                    ? 'bg-white text-slate-900 shadow-xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                مكالمة
              </button>
              <button
                type="button"
                onClick={() => setManualKind('sms')}
                className={`rounded-md px-3 py-1 text-xs font-semibold whitespace-nowrap shrink-0 ${
                  manualKind === 'sms'
                    ? 'bg-white text-slate-900 shadow-xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                رسالة نصية
              </button>
            </div>
          </div>

          {manualKind === 'call' ? (
            <div className="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-2">
              <div>
                <label className="block text-xs font-semibold text-slate-700">رقم الهاتف</label>
                <input
                  type="text"
                  dir="ltr"
                  value={callNumber}
                  onChange={(e) => setCallNumber(e.target.value)}
                  placeholder="+9665..."
                  className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-700">
                  الاسم (اختياري)
                </label>
                <input
                  type="text"
                  value={callName}
                  onChange={(e) => setCallName(e.target.value)}
                  placeholder="اسم جهة الاتصال"
                  className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-700">نوع المكالمة</label>
                <select
                  value={callType}
                  onChange={(e) => setCallType(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
                >
                  <option value="incoming">واردة</option>
                  <option value="outgoing">صادرة</option>
                  <option value="missed">فائتة</option>
                  <option value="rejected">مرفوضة</option>
                </select>
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-700">المدة (بالثواني)</label>
                <input
                  type="number"
                  min={0}
                  value={callDuration}
                  onChange={(e) => setCallDuration(e.target.value)}
                  className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs text-slate-900 tabular-nums focus:border-[#1F3A5F] focus:outline-none"
                />
              </div>
            </div>
          ) : (
            <div className="mt-4 space-y-3">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <div>
                  <label className="block text-xs font-semibold text-slate-700">
                    رقم المرسل / المستلم
                  </label>
                  <input
                    type="text"
                    dir="ltr"
                    value={smsAddress}
                    onChange={(e) => setSmsAddress(e.target.value)}
                    placeholder="+9665..."
                    className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 font-mono text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
                  />
                </div>
                <div>
                  <label className="block text-xs font-semibold text-slate-700">نوع الرسالة</label>
                  <select
                    value={smsType}
                    onChange={(e) => setSmsType(e.target.value)}
                    className="mt-1 w-full rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
                  >
                    <option value="inbox">واردة</option>
                    <option value="sent">مرسلة</option>
                    <option value="draft">مسودة</option>
                  </select>
                </div>
              </div>
              <div>
                <label className="block text-xs font-semibold text-slate-700">نص الرسالة</label>
                <textarea
                  rows={2}
                  value={smsBody}
                  onChange={(e) => setSmsBody(e.target.value)}
                  placeholder="محتوى الرسالة النصية..."
                  className="mt-1 w-full rounded-lg border border-slate-200 px-3 py-2 text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
                />
              </div>
            </div>
          )}

          <div className="mt-4">
            <button
              type="submit"
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-slate-50 px-4 py-2 text-xs font-semibold text-slate-800 hover:bg-slate-100 whitespace-nowrap shrink-0"
            >
              <Plus className="h-3.5 w-3.5" />
              <span>حفظ السجل ورفعه مشفّرًا</span>
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};
