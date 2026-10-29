import React, { useEffect, useState } from 'react';
import { Download, FolderOpen, PlusCircle, Smartphone, Trash2 } from 'lucide-react';
import { CloudRepo } from '../core/cloudRepo';
import { TimeFmt } from '../core/csvAndTime';
import { friendlyErrorMessageAr } from '../core/firebase';
import { recordTypeAr } from '../core/labels';
import { DeviceInfo, RecordType } from '../core/types';
import { ConfirmModal } from './Dialogs';

interface DeviceCounts {
  call: number;
  sms: number;
  clip: number;
  total: number;
}

interface DevicesViewProps {
  currentDeviceId: string;
  refreshSignal: number;
  onInspectDevice: (device: DeviceInfo) => void;
  onExportDevice: (device: DeviceInfo) => void;
  onGoToBackupSource: () => void;
}

export const DevicesView: React.FC<DevicesViewProps> = ({
  currentDeviceId,
  refreshSignal,
  onInspectDevice,
  onExportDevice,
  onGoToBackupSource,
}) => {
  const [devices, setDevices] = useState<DeviceInfo[] | null>(null);
  const [countsMap, setCountsMap] = useState<Record<string, DeviceCounts>>({});
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmState, setConfirmState] = useState<{
    kind: 'cloud' | 'device';
    device: DeviceInfo;
  } | null>(null);
  const [busyAction, setBusyAction] = useState(false);

  // Live real-time subscription to registered devices in Cloud Firestore
  useEffect(() => {
    setLoading(true);
    setError(null);
    const unsub = CloudRepo.subscribeDevices(
      (list) => {
        setDevices(list);
        setLoading(false);
      },
      (err) => {
        setError(friendlyErrorMessageAr(err));
        setLoading(false);
      }
    );
    return () => unsub();
  }, [refreshSignal]);

  // Live real-time record counts per device
  useEffect(() => {
    if (!devices || devices.length === 0) {
      setCountsMap({});
      return;
    }
    const unsubs = devices.map((dev) =>
      CloudRepo.subscribeDeviceRecords(
        dev.id,
        (recs) => {
          const counts: DeviceCounts = { call: 0, sms: 0, clip: 0, total: recs.length };
          for (const r of recs) {
            if (r.type === RecordType.CALL) counts.call++;
            else if (r.type === RecordType.SMS) counts.sms++;
            else if (r.type === RecordType.CLIP) counts.clip++;
          }
          setCountsMap((prev) => ({ ...prev, [dev.id]: counts }));
        },
        (err) => {
          setError(friendlyErrorMessageAr(err));
        }
      )
    );
    return () => {
      unsubs.forEach((u) => u());
    };
  }, [devices]);

  const handleConfirmDelete = async () => {
    if (!confirmState) return;
    setBusyAction(true);
    setNotice(null);
    setError(null);
    try {
      if (confirmState.kind === 'cloud') {
        await CloudRepo.deleteDeviceRecords(confirmState.device.id);
        setNotice(
          `حُذفت النسخة السحابية للجهاز «${confirmState.device.name}». الملفات المنزَّلة سابقًا لم تتأثر.`
        );
      } else {
        await CloudRepo.deleteDevice(confirmState.device.id);
        setNotice(`أُزيل الجهاز «${confirmState.device.name}» من الحساب مع بياناته السحابية.`);
      }
      setConfirmState(null);
    } catch (err) {
      setError(friendlyErrorMessageAr(err));
    } finally {
      setBusyAction(false);
    }
  };

  const totalCloudRecords = Object.values(countsMap).reduce((acc, c) => acc + c.total, 0);

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4 border-b border-slate-200 pb-5">
        <div>
          <h1 className="text-2xl font-bold text-slate-900">الأجهزة والنسخ السحابية</h1>
          <p className="mt-1 text-sm text-slate-600">
            مزامنة لحظية مباشرة مع السحابة · إجمالي السجلات المشفّرة المحفوظة:{' '}
            <span className="font-mono font-semibold text-slate-900 tabular-nums">
              {loading ? '…' : totalCloudRecords}
            </span>
          </p>
        </div>

        <div className="flex items-center gap-2.5">
          <button
            type="button"
            onClick={onGoToBackupSource}
            className="inline-flex items-center gap-2 rounded-lg bg-[#1F3A5F] px-4 py-2 text-xs font-semibold text-white hover:bg-[#162a47] whitespace-nowrap shrink-0"
          >
            <PlusCircle className="h-3.5 w-3.5" />
            <span>إضافة أو استيراد بيانات</span>
          </button>
        </div>
      </div>

      {notice && (
        <div className="rounded-lg border border-emerald-200 bg-emerald-50 px-4 py-3 text-sm text-emerald-900">
          {notice}
        </div>
      )}

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading && !devices && (
        <div className="grid grid-cols-1 gap-4 md:grid-cols-2">
          {[1, 2].map((n) => (
            <div key={n} className="rounded-xl border border-slate-200 bg-white p-6">
              <div className="h-5 w-40 rounded bg-slate-200" />
              <div className="mt-2 h-3.5 w-28 rounded bg-slate-100" />
              <div className="mt-5 h-4 w-full rounded bg-slate-100" />
              <div className="mt-2 h-4 w-2/3 rounded bg-slate-100" />
            </div>
          ))}
        </div>
      )}

      {!loading && devices && devices.length === 0 && (
        <div className="rounded-xl border border-slate-200 bg-white p-10 text-center">
          <Smartphone className="mx-auto h-8 w-8 text-slate-400" />
          <h2 className="mt-3 text-base font-bold text-slate-900">لا توجد أجهزة مسجّلة بعد</h2>
          <p className="mx-auto mt-1.5 max-w-md text-sm text-slate-600">
            استورد ملفات المكالمات والرسائل المصدّرة من هاتفك (`XML` أو `CSV` أو `JSON`) أو احفظ
            نصوص الحافظة لرفعها مشفّرة إلى حسابك السحابي.
          </p>
          <button
            type="button"
            onClick={onGoToBackupSource}
            className="mt-5 inline-flex items-center gap-2 rounded-lg bg-[#1F3A5F] px-5 py-2.5 text-sm font-semibold text-white hover:bg-[#162a47] whitespace-nowrap shrink-0"
          >
            <PlusCircle className="h-4 w-4" />
            <span>بدء النسخ الاحتياطي أو الاستيراد الآن</span>
          </button>
        </div>
      )}

      {devices && devices.length > 0 && (
        <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
          {devices.map((dev) => {
            const counts = countsMap[dev.id];
            const isThisBrowser = dev.id === currentDeviceId;
            return (
              <div
                key={dev.id}
                className="flex flex-col justify-between rounded-xl border border-slate-200 bg-white p-6"
              >
                <div>
                  <div className="flex items-start justify-between gap-4">
                    <div>
                      <h2 className="text-lg font-bold text-slate-900">{dev.name}</h2>
                      <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
                        <span>{dev.model || 'جهاز مسجّل'}</span>
                        {isThisBrowser && (
                          <>
                            <span aria-hidden="true">·</span>
                            <span className="font-semibold text-[#1F3A5F]">هذا المتصفح الحالي</span>
                          </>
                        )}
                      </div>
                    </div>
                    <div className="text-left">
                      <div className="font-mono text-2xl font-bold text-slate-900 tabular-nums">
                        {counts ? counts.total : '…'}
                      </div>
                      <div className="text-xs text-slate-500">إجمالي السجلات</div>
                    </div>
                  </div>

                  <div className="mt-5 space-y-2 border-t border-slate-100 pt-4 text-xs text-slate-600">
                    <div className="flex flex-wrap items-center gap-2">
                      <span>
                        المكالمات:{' '}
                        <strong className="font-mono text-slate-900 tabular-nums">
                          {counts ? counts.call : '…'}
                        </strong>
                      </span>
                      <span aria-hidden="true">·</span>
                      <span>
                        الرسائل:{' '}
                        <strong className="font-mono text-slate-900 tabular-nums">
                          {counts ? counts.sms : '…'}
                        </strong>
                      </span>
                      <span aria-hidden="true">·</span>
                      <span>
                        الحافظة:{' '}
                        <strong className="font-mono text-slate-900 tabular-nums">
                          {counts ? counts.clip : '…'}
                        </strong>
                      </span>
                    </div>

                    <div>
                      الأنواع المفعّلة:{' '}
                      <span className="text-slate-800">
                        {dev.enabled.length > 0
                          ? dev.enabled.map((t) => recordTypeAr(t)).join(' · ')
                          : 'لا يوجد'}
                      </span>
                    </div>

                    <div>
                      آخر مزامنة ناجحة:{' '}
                      <span className="font-mono text-slate-800 tabular-nums">
                        {TimeFmt.human(dev.lastSyncAt)}
                      </span>
                    </div>
                  </div>
                </div>

                <div className="mt-6 flex flex-wrap items-center justify-between gap-3 border-t border-slate-100 pt-4">
                  <div className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => onInspectDevice(dev)}
                      className="inline-flex items-center gap-1.5 rounded-lg bg-[#1F3A5F] px-3.5 py-2 text-xs font-semibold text-white hover:bg-[#162a47] whitespace-nowrap shrink-0"
                    >
                      <FolderOpen className="h-3.5 w-3.5" />
                      <span>استعراض البيانات</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => onExportDevice(dev)}
                      className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 hover:bg-slate-50 whitespace-nowrap shrink-0"
                    >
                      <Download className="h-3.5 w-3.5" />
                      <span>تنزيل النسخة</span>
                    </button>
                  </div>

                  <div className="flex items-center gap-3 text-xs">
                    <button
                      type="button"
                      onClick={() => setConfirmState({ kind: 'cloud', device: dev })}
                      className="font-semibold text-slate-500 hover:text-red-600 whitespace-nowrap shrink-0"
                    >
                      مسح النسخة السحابية
                    </button>
                    <span className="text-slate-300" aria-hidden="true">
                      |
                    </span>
                    <button
                      type="button"
                      onClick={() => setConfirmState({ kind: 'device', device: dev })}
                      className="inline-flex items-center gap-1 font-semibold text-red-600 hover:text-red-700 whitespace-nowrap shrink-0"
                    >
                      <Trash2 className="h-3.5 w-3.5" />
                      <span>إزالة الجهاز</span>
                    </button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {confirmState?.kind === 'cloud' && (
        <ConfirmModal
          title="حذف النسخة السحابية للجهاز"
          description={`سيُحذف كل ما رُفع من «${confirmState.device.name}» من السحابة فقط. السجلات المحلية على الجهاز الأصلي وأي ملفات نزّلتها سابقًا إلى ذاكرتك لن تُحذف.`}
          confirmLabel="حذف النسخة السحابية"
          danger
          busy={busyAction}
          onConfirm={() => void handleConfirmDelete()}
          onClose={() => setConfirmState(null)}
        />
      )}

      {confirmState?.kind === 'device' && (
        <ConfirmModal
          title="إزالة الجهاز من الحساب"
          description={`سيُحذف الجهاز «${confirmState.device.name}» وجميع سجلاته السحابية من حسابك. الملفات المنزَّلة سابقًا إلى جهازك لن تُحذف.`}
          confirmLabel="إزالة الجهاز"
          danger
          busy={busyAction}
          onConfirm={() => void handleConfirmDelete()}
          onClose={() => setConfirmState(null)}
        />
      )}
    </div>
  );
};
