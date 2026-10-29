import { ClipResult, PeriodFilter, RecordType } from './types';

export function recordTypeAr(type: RecordType | string): string {
  switch (type) {
    case RecordType.CALL:
      return 'المكالمات';
    case RecordType.SMS:
      return 'الرسائل النصية';
    case RecordType.CLIP:
      return 'الحافظة';
    default:
      return String(type);
  }
}

export function recordTypeLongAr(type: RecordType): string {
  switch (type) {
    case RecordType.CALL:
      return 'سجل المكالمات';
    case RecordType.SMS:
      return 'الرسائل النصية';
    case RecordType.CLIP:
      return 'نصوص الحافظة';
  }
}

export function callTypeAr(type: string): string {
  switch (type) {
    case 'incoming':
      return 'واردة';
    case 'outgoing':
      return 'صادرة';
    case 'missed':
      return 'فائتة';
    case 'rejected':
      return 'مرفوضة';
    case 'blocked':
      return 'محظورة';
    case 'voicemail':
      return 'بريد صوتي';
    default:
      return 'أخرى';
  }
}

export function smsTypeAr(type: string): string {
  switch (type) {
    case 'inbox':
      return 'واردة';
    case 'sent':
      return 'مرسلة';
    case 'draft':
      return 'مسودة';
    case 'outbox':
      return 'صادر';
    case 'failed':
      return 'فاشلة';
    case 'queued':
      return 'بالانتظار';
    default:
      return 'أخرى';
  }
}

export function clipResultMessageAr(res: ClipResult): string {
  switch (res) {
    case ClipResult.SAVED:
      return 'تم حفظ نص الحافظة محليًا وجدولته للرفع المشفّر.';
    case ClipResult.DUPLICATE:
      return 'هذا النص محفوظ مسبقًا (تم منع التكرار تلقائيًا).';
    case ClipResult.SENSITIVE:
      return 'تم حجب النص تلقائيًا لأنه يبدو كلمة مرور أو رمز تحقق أو رقم بطاقة بنكية.';
    case ClipResult.EMPTY:
      return 'الحقل فارغ — أدخل نصًا أو الصقه أولًا.';
    case ClipResult.TOO_LONG:
      return 'النص يتجاوز الحد الأقصى المسموح به (100,000 حرف).';
    case ClipResult.DISABLED:
      return 'نسخ الحافظة معطّل حاليًا في إعدادات أنواع البيانات.';
  }
}

export const PERIOD_OPTIONS: Array<{ id: PeriodFilter; label: string; days: number | null }> = [
  { id: PeriodFilter.ALL, label: 'الكل', days: null },
  { id: PeriodFilter.D7, label: '7 أيام', days: 7 },
  { id: PeriodFilter.D30, label: '30 يومًا', days: 30 },
  { id: PeriodFilter.Y1, label: 'سنة', days: 365 },
];
