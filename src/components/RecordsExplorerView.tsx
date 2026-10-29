import React, { useEffect, useMemo, useState } from 'react';
import { ArrowRight, Download, Search, Trash2 } from 'lucide-react';
import { CloudRepo } from '../core/cloudRepo';
import { decryptRecord } from '../core/crypto';
import { TimeFmt } from '../core/csvAndTime';
import { friendlyErrorMessageAr } from '../core/firebase';
import { callTypeAr, PERIOD_OPTIONS, recordTypeAr, smsTypeAr } from '../core/labels';
import { DeviceInfo, PeriodFilter, PlainRecord, RecordType } from '../core/types';
import { ConfirmModal } from './Dialogs';

interface RecordsExplorerViewProps {
  vaultKey: CryptoKey;
  selectedDevice: DeviceInfo | null;
  onSelectDevice: (device: DeviceInfo) => void;
  onBackToDevices: () => void;
  onOpenExport: (device: DeviceInfo) => void;
}

const PAGE_STEP = 50;

export const RecordsExplorerView: React.FC<RecordsExplorerViewProps> = ({
  vaultKey,
  selectedDevice,
  onSelectDevice,
  onBackToDevices,
  onOpenExport,
}) => {
  const [devices, setDevices] = useState<DeviceInfo[]>([]);
  const [activeTab, setActiveTab] = useState<RecordType>(RecordType.CALL);
  const [period, setPeriod] = useState<PeriodFilter>(PeriodFilter.ALL);
  const [searchQuery, setSearchQuery] = useState('');
  const [decryptedAll, setDecryptedAll] = useState<PlainRecord[]>([]);
  const [visibleCount, setVisibleCount] = useState(PAGE_STEP);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [recordToDelete, setRecordToDelete] = useState<PlainRecord | null>(null);
  const [deleting, setDeleting] = useState(false);

  // Real-time device list subscription
  useEffect(() => {
    const unsub = CloudRepo.subscribeDevices(
      (list) => {
        setDevices(list);
        if (!selectedDevice && list.length > 0) {
          onSelectDevice(list[0]);
        }
      },
      (err) => setError(friendlyErrorMessageAr(err))
    );
    return () => unsub();
  }, [selectedDevice?.id]);

  // Real-time encrypted records subscription + client-side AES-256-GCM decryption
  useEffect(() => {
    if (!selectedDevice) {
      setDecryptedAll([]);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);

    const unsub = CloudRepo.subscribeDeviceRecords(
      selectedDevice.id,
      (encList) => {
        void (async () => {
          try {
            const plainList: PlainRecord[] = [];
            for (const enc of encList) {
              plainList.push(await decryptRecord(vaultKey, selectedDevice.id, enc));
            }
            if (!cancelled) {
              setDecryptedAll(plainList);
              setLoading(false);
            }
          } catch (err) {
            if (!cancelled) {
              setError(friendlyErrorMessageAr(err));
              setLoading(false);
            }
          }
        })();
      },
      (err) => {
        if (!cancelled) {
          setError(friendlyErrorMessageAr(err));
          setLoading(false);
        }
      }
    );

    return () => {
      cancelled = true;
      unsub();
    };
  }, [selectedDevice?.id, vaultKey]);

  useEffect(() => {
    setVisibleCount(PAGE_STEP);
  }, [activeTab, period, searchQuery]);

  const filteredRecords = useMemo(() => {
    const periodMeta = PERIOD_OPTIONS.find((p) => p.id === period);
    const minTs = periodMeta?.days ? Date.now() - periodMeta.days * 86_400_000 : null;
    const q = searchQuery.trim().toLowerCase();

    return decryptedAll.filter((r) => {
      if (r.type !== activeTab) return false;
      if (minTs != null && r.ts < minTs) return false;
      if (!q) return true;
      const haystack = Object.values(r.fields)
        .map((v) => String(v ?? ''))
        .join(' ')
        .toLowerCase();
      return haystack.includes(q);
    });
  }, [decryptedAll, activeTab, period, searchQuery]);

  const visibleRecords = filteredRecords.slice(0, visibleCount);

  const handleConfirmDeleteRecord = async () => {
    if (!recordToDelete || !selectedDevice) return;
    setDeleting(true);
    setError(null);
    try {
      await CloudRepo.deleteRecord(selectedDevice.id, recordToDelete.id);
      setRecordToDelete(null);
    } catch (err) {
      setError(friendlyErrorMessageAr(err));
    } finally {
      setDeleting(false);
    }
  };

  if (!selectedDevice && devices.length === 0 && !loading) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-10 text-center">
        <h2 className="text-base font-bold text-slate-900">لا يوجد جهاز محدد للاستعراض</h2>
        <p className="mt-1.5 text-sm text-slate-600">
          انتقل إلى قسم النسخ والاستيراد لحفظ أو استيراد سجلاتك المشفّرة في حسابك.
        </p>
        <button
          type="button"
          onClick={onBackToDevices}
          className="mt-4 rounded-lg border border-slate-200 bg-white px-4 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 whitespace-nowrap shrink-0"
        >
          العودة إلى قائمة الأجهزة
        </button>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 pb-5">
        <div className="flex flex-wrap items-center gap-3">
          <button
            type="button"
            onClick={onBackToDevices}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 whitespace-nowrap shrink-0"
          >
            <ArrowRight className="h-3.5 w-3.5" />
            <span>الأجهزة</span>
          </button>

          {devices.length > 0 && (
            <select
              aria-label="اختيار الجهاز"
              value={selectedDevice?.id ?? ''}
              onChange={(e) => {
                const found = devices.find((d) => d.id === e.target.value);
                if (found) onSelectDevice(found);
              }}
              className="rounded-lg border border-slate-200 bg-white px-3.5 py-2 text-sm font-bold text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
            >
              {devices.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name} ({d.model || d.id})
                </option>
              ))}
            </select>
          )}
        </div>

        {selectedDevice && (
          <button
            type="button"
            onClick={() => onOpenExport(selectedDevice)}
            className="inline-flex items-center gap-2 rounded-lg bg-[#1F3A5F] px-4 py-2 text-xs font-semibold text-white hover:bg-[#162a47] whitespace-nowrap shrink-0"
          >
            <Download className="h-3.5 w-3.5" />
            <span>تنزيل نسخة هذا الجهاز</span>
          </button>
        )}
      </div>

      <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex rounded-lg bg-slate-100 p-1">
            {[RecordType.CALL, RecordType.SMS, RecordType.CLIP].map((t) => {
              const countForTab = decryptedAll.filter((r) => r.type === t).length;
              return (
                <button
                  key={t}
                  type="button"
                  onClick={() => setActiveTab(t)}
                  className={`rounded-md px-3.5 py-1.5 text-xs font-semibold transition-colors whitespace-nowrap shrink-0 ${
                    activeTab === t
                      ? 'bg-white text-slate-900 shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  {recordTypeAr(t)}{' '}
                  <span className="font-mono text-[11px] text-slate-500 tabular-nums">
                    ({countForTab})
                  </span>
                </button>
              );
            })}
          </div>

          <div className="inline-flex rounded-lg bg-slate-100 p-1">
            {PERIOD_OPTIONS.map((p) => (
              <button
                key={p.id}
                type="button"
                onClick={() => setPeriod(p.id)}
                className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors whitespace-nowrap shrink-0 ${
                  period === p.id
                    ? 'bg-white text-slate-900 shadow-xs'
                    : 'text-slate-600 hover:text-slate-900'
                }`}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>

        <div className="relative w-full lg:w-72">
          <Search className="pointer-events-none absolute top-2.5 right-3 h-4 w-4 text-slate-400" />
          <input
            type="search"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="بحث في الأرقام أو الأسماء أو النصوص..."
            className="w-full rounded-lg border border-slate-200 bg-white py-2 pr-9 pl-3 text-xs text-slate-900 focus:border-[#1F3A5F] focus:outline-none"
          />
        </div>
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      <div className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        {loading ? (
          <div className="divide-y divide-slate-100 p-6">
            {[1, 2, 3, 4].map((idx) => (
              <div key={idx} className="py-3">
                <div className="h-4 w-48 rounded bg-slate-200" />
                <div className="mt-2 h-3.5 w-3/4 rounded bg-slate-100" />
              </div>
            ))}
          </div>
        ) : filteredRecords.length === 0 ? (
          <div className="p-10 text-center text-sm text-slate-500">
            لا توجد سجلات في قسم «{recordTypeAr(activeTab)}» ضمن الفترة أو البحث المحدّد.
          </div>
        ) : (
          <div className="divide-y divide-slate-100">
            {visibleRecords.map((rec) => {
              const f = rec.fields;
              return (
                <div
                  key={rec.id}
                  className="flex items-start justify-between gap-4 px-5 py-3.5 transition-colors hover:bg-slate-50/70"
                >
                  <div className="min-w-0 flex-1">
                    {rec.type === RecordType.CALL && (
                      <>
                        <div className="text-sm font-semibold text-slate-900">
                          {String(f.name || '').trim() ||
                            String(f.number || '').trim() ||
                            'رقم مخفي'}
                        </div>
                        <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-600">
                          <span>{callTypeAr(String(f.type ?? ''))}</span>
                          <span aria-hidden="true">·</span>
                          <span className="font-mono tabular-nums">
                            {Number(f.durationSec ?? 0)} ثانية
                          </span>
                          {String(f.number || '').trim() && (
                            <>
                              <span aria-hidden="true">·</span>
                              <span dir="ltr" className="font-mono text-slate-700 tabular-nums">
                                {String(f.number)}
                              </span>
                            </>
                          )}
                          <span aria-hidden="true">·</span>
                          <span className="font-mono text-slate-500 tabular-nums">
                            {TimeFmt.human(rec.ts)}
                          </span>
                        </div>
                      </>
                    )}

                    {rec.type === RecordType.SMS && (
                      <>
                        <div className="flex flex-wrap items-center gap-2 text-xs text-slate-600">
                          <strong
                            dir="ltr"
                            className="font-mono text-sm font-semibold text-slate-900 tabular-nums"
                          >
                            {String(f.address ?? '')}
                          </strong>
                          <span aria-hidden="true">·</span>
                          <span>{smsTypeAr(String(f.type ?? ''))}</span>
                          <span aria-hidden="true">·</span>
                          <span className="font-mono text-slate-500 tabular-nums">
                            {TimeFmt.human(rec.ts)}
                          </span>
                        </div>
                        <p className="mt-1.5 whitespace-pre-wrap break-words text-sm leading-relaxed text-slate-800">
                          {String(f.body ?? '')}
                        </p>
                      </>
                    )}

                    {rec.type === RecordType.CLIP && (
                      <>
                        <p className="whitespace-pre-wrap break-words text-sm leading-relaxed text-slate-800">
                          {String(f.text ?? '')}
                        </p>
                        <div className="mt-1.5 font-mono text-xs text-slate-500 tabular-nums">
                          {TimeFmt.human(rec.ts)}
                        </div>
                      </>
                    )}
                  </div>

                  <button
                    type="button"
                    onClick={() => setRecordToDelete(rec)}
                    className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1.5 text-xs font-semibold text-slate-500 hover:bg-red-50 hover:text-red-600 whitespace-nowrap shrink-0"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    <span>حذف</span>
                  </button>
                </div>
              );
            })}
          </div>
        )}

        {!loading && visibleCount < filteredRecords.length && (
          <div className="border-t border-slate-200 bg-slate-50 p-3 text-center">
            <button
              type="button"
              onClick={() => setVisibleCount((prev) => prev + PAGE_STEP)}
              className="rounded-lg border border-slate-200 bg-white px-4 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 whitespace-nowrap shrink-0"
            >
              عرض {Math.min(PAGE_STEP, filteredRecords.length - visibleCount)} سجلًا إضافيًا (المعروض{' '}
              <span className="font-mono tabular-nums">{visibleRecords.length}</span> من{' '}
              <span className="font-mono tabular-nums">{filteredRecords.length}</span>)
            </button>
          </div>
        )}
      </div>

      {recordToDelete && (
        <ConfirmModal
          title="حذف السجل من السحابة"
          description="سيُحذف هذا السجل المشفّر من السحابة نهائيًا. أي ملفات صدّرتها أو نزّلتها سابقًا إلى جهازك لن تتأثر."
          confirmLabel="حذف السجل"
          danger
          busy={deleting}
          onConfirm={() => void handleConfirmDeleteRecord()}
          onClose={() => setRecordToDelete(null)}
        />
      )}
    </div>
  );
};
