import React, { useState } from 'react';
import { Check, Download, ShieldAlert, X } from 'lucide-react';
import { CloudRepo } from '../core/cloudRepo';
import { runVerifiedExport } from '../core/exporter';
import { friendlyErrorMessageAr } from '../core/firebase';
import { PERIOD_OPTIONS, recordTypeAr } from '../core/labels';
import {
  DeviceInfo,
  ExportFormat,
  ExportOutcome,
  PeriodFilter,
  RecordType,
} from '../core/types';

interface ConfirmModalProps {
  title: string;
  description: string;
  confirmLabel: string;
  danger?: boolean;
  busy?: boolean;
  onConfirm: () => void;
  onClose: () => void;
}

export const ConfirmModal: React.FC<ConfirmModalProps> = ({
  title,
  description,
  confirmLabel,
  danger = false,
  busy = false,
  onConfirm,
  onClose,
}) => {
  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="w-full max-w-md rounded-xl border border-slate-200 bg-white p-6">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-2.5">
            {danger && <ShieldAlert className="h-5 w-5 shrink-0 text-red-600" />}
            <h3 className="text-lg font-bold text-slate-900">{title}</h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="rounded-lg p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-900 disabled:opacity-50"
            aria-label="إغلاق"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <p className="mt-3 text-sm leading-relaxed text-slate-600">{description}</p>

        <div className="mt-6 flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={onClose}
            disabled={busy}
            className="rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 whitespace-nowrap shrink-0"
          >
            إلغاء
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={busy}
            className={`rounded-lg px-4 py-2 text-sm font-semibold text-white transition-colors disabled:opacity-50 whitespace-nowrap shrink-0 ${
              danger ? 'bg-red-600 hover:bg-red-700' : 'bg-[#1F3A5F] hover:bg-[#162a47]'
            }`}
          >
            {busy ? 'جاري التنفيذ...' : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
};

interface ExportModalProps {
  device: DeviceInfo;
  vaultKey: CryptoKey;
  onClose: () => void;
}

export const ExportModal: React.FC<ExportModalProps> = ({ device, vaultKey, onClose }) => {
  const [selectedTypes, setSelectedTypes] = useState<RecordType[]>([
    RecordType.CALL,
    RecordType.SMS,
    RecordType.CLIP,
  ]);
  const [selectedFormats, setSelectedFormats] = useState<ExportFormat[]>(['csv', 'json']);
  const [period, setPeriod] = useState<PeriodFilter>(PeriodFilter.ALL);
  const [preferZip, setPreferZip] = useState<boolean>(true);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [outcome, setOutcome] = useState<ExportOutcome | null>(null);
  const [error, setError] = useState<string | null>(null);

  const mustZip = selectedTypes.length * selectedFormats.length > 1;
  const useZip = preferZip || mustZip;

  const toggleType = (t: RecordType) => {
    setSelectedTypes((prev) => (prev.includes(t) ? prev.filter((x) => x !== t) : [...prev, t]));
  };

  const toggleFormat = (f: ExportFormat) => {
    setSelectedFormats((prev) => (prev.includes(f) ? prev.filter((x) => x !== f) : [...prev, f]));
  };

  const handleStartExport = async () => {
    if (selectedTypes.length === 0 || selectedFormats.length === 0) return;
    setError(null);
    setOutcome(null);
    setProgress({ done: 0, total: 1 });

    try {
      const periodMeta = PERIOD_OPTIONS.find((p) => p.id === period);
      const fromTs = periodMeta?.days ? Date.now() - periodMeta.days * 86_400_000 : null;
      const encryptedRecords = await CloudRepo.listDeviceRecords(device.id);
      const res = await runVerifiedExport(
        {
          deviceId: device.id,
          deviceName: device.name,
          types: selectedTypes,
          formats: selectedFormats,
          from: fromTs,
          to: null,
          zip: useZip,
        },
        vaultKey,
        encryptedRecords,
        (done, total) => setProgress({ done, total })
      );
      setOutcome(res);
    } catch (err) {
      setError(friendlyErrorMessageAr(err));
    } finally {
      setProgress(null);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/50 p-4"
      role="dialog"
      aria-modal="true"
    >
      <div className="max-h-[90vh] w-full max-w-xl overflow-y-auto rounded-xl border border-slate-200 bg-white p-6">
        <div className="flex items-start justify-between gap-4 border-b border-slate-200 pb-4">
          <div>
            <h3 className="text-lg font-bold text-slate-900">تنزيل نسخة «{device.name}»</h3>
            <p className="mt-1 text-xs text-slate-500">
              يُفك التشفير محليًا داخل المتصفح مع التحقق من بصمة SHA-256 لكل ملف قبل الحفظ.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={progress !== null}
            className="rounded-lg p-1 text-slate-500 hover:bg-slate-100 hover:text-slate-900 disabled:opacity-50"
            aria-label="إغلاق"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="mt-5 space-y-5">
          <div>
            <div className="text-sm font-semibold text-slate-900">أقسام البيانات</div>
            <div className="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-3">
              {[RecordType.CALL, RecordType.SMS, RecordType.CLIP].map((t) => {
                const active = selectedTypes.includes(t);
                return (
                  <label
                    key={t}
                    className={`flex cursor-pointer items-center gap-2.5 rounded-lg border px-3 py-2.5 text-sm transition-colors ${
                      active
                        ? 'border-[#1F3A5F] bg-slate-50 font-semibold text-slate-900'
                        : 'border-slate-200 text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={active}
                      onChange={() => toggleType(t)}
                      className="h-4 w-4 accent-[#1F3A5F]"
                    />
                    <span className="whitespace-nowrap">{recordTypeAr(t)}</span>
                  </label>
                );
              })}
            </div>
          </div>

          <div>
            <div className="text-sm font-semibold text-slate-900">صيغ الملفات</div>
            <div className="mt-2 grid grid-cols-3 gap-2">
              {(['csv', 'json', 'txt'] as ExportFormat[]).map((fmt) => {
                const active = selectedFormats.includes(fmt);
                return (
                  <label
                    key={fmt}
                    className={`flex cursor-pointer items-center gap-2.5 rounded-lg border px-3 py-2.5 text-sm transition-colors ${
                      active
                        ? 'border-[#1F3A5F] bg-slate-50 font-semibold text-slate-900'
                        : 'border-slate-200 text-slate-600 hover:bg-slate-50'
                    }`}
                  >
                    <input
                      type="checkbox"
                      checked={active}
                      onChange={() => toggleFormat(fmt)}
                      className="h-4 w-4 accent-[#1F3A5F]"
                    />
                    <span className="font-mono uppercase">{fmt}</span>
                  </label>
                );
              })}
            </div>
          </div>

          <div>
            <div className="text-sm font-semibold text-slate-900">الفترة الزمنية</div>
            <div className="mt-2 inline-flex rounded-lg bg-slate-100 p-1">
              {PERIOD_OPTIONS.map((opt) => (
                <button
                  key={opt.id}
                  type="button"
                  onClick={() => setPeriod(opt.id)}
                  className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-colors whitespace-nowrap shrink-0 ${
                    period === opt.id
                      ? 'bg-white text-slate-900 shadow-xs'
                      : 'text-slate-600 hover:text-slate-900'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
          </div>

          <label className="flex cursor-pointer items-center gap-2.5 text-sm text-slate-700">
            <input
              type="checkbox"
              checked={useZip}
              disabled={mustZip}
              onChange={(e) => setPreferZip(e.target.checked)}
              className="h-4 w-4 accent-[#1F3A5F]"
            />
            <span>
              ملف <span className="font-mono">ZIP</span> مجمّع مع <span className="font-mono">manifest.json</span>
              {mustZip ? ' (مطلوب تلقائيًا عند اختيار أكثر من ملف)' : ''}
            </span>
          </label>

          {progress && (
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <div className="flex items-center justify-between text-xs text-slate-700">
                <span>جاري فك التشفير والتحقق من سلامة الملفات...</span>
                <span className="font-mono tabular-nums">
                  {progress.done} / {progress.total} سجل
                </span>
              </div>
              <div className="mt-2 h-2 w-full overflow-hidden rounded-full bg-slate-200">
                <div
                  className="h-full bg-[#1F3A5F] transition-transform duration-150"
                  style={{
                    transform: `scaleX(${
                      progress.total === 0 ? 1 : Math.min(1, progress.done / progress.total)
                    })`,
                    transformOrigin: 'right',
                  }}
                />
              </div>
            </div>
          )}

          {error && (
            <div className="rounded-lg border border-red-200 bg-red-50 px-3.5 py-2.5 text-sm text-red-700">
              فشل التنزيل: {error}
            </div>
          )}

          {outcome && (
            <div className="rounded-lg border border-emerald-200 bg-emerald-50/70 p-4 text-sm text-emerald-950">
              <div className="flex items-center gap-2 font-semibold text-emerald-800">
                <Check className="h-4 w-4 shrink-0" />
                <span>
                  تم تصدير <span className="font-mono tabular-nums">{outcome.recordCount}</span> سجل في{' '}
                  <span className="font-mono tabular-nums">{outcome.fileCount}</span> ملف، وجرى التحقق من
                  مطابقة بصمات SHA-256.
                </span>
              </div>
              <div className="mt-3 space-y-1 border-t border-emerald-200/80 pt-2.5 text-xs">
                {Object.entries(outcome.verifiedHashes).map(([name, hash]) => (
                  <div key={name} className="flex items-center justify-between gap-4 font-mono">
                    <span className="font-semibold text-emerald-900">{name}</span>
                    <span className="truncate text-emerald-700 tabular-nums" title={hash}>
                      {hash.slice(0, 20)}…
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        <div className="mt-6 flex items-center justify-end gap-3 border-t border-slate-200 pt-4">
          <button
            type="button"
            onClick={onClose}
            disabled={progress !== null}
            className="rounded-lg border border-slate-200 bg-white px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50 whitespace-nowrap shrink-0"
          >
            إغلاق
          </button>
          <button
            type="button"
            onClick={handleStartExport}
            disabled={
              progress !== null || selectedTypes.length === 0 || selectedFormats.length === 0
            }
            className="inline-flex items-center gap-2 rounded-lg bg-[#1F3A5F] px-4 py-2 text-sm font-semibold text-white hover:bg-[#162a47] disabled:opacity-50 whitespace-nowrap shrink-0"
          >
            <Download className="h-4 w-4" />
            <span>تنزيل وحفظ الملفات</span>
          </button>
        </div>
      </div>
    </div>
  );
};
