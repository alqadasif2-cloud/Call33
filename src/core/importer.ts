import { makeRecordId } from './crypto';
import { Csv, TimeFmt } from './csvAndTime';
import { PlainRecord, RecordType } from './types';

export function callTypeFromRaw(raw: string): string {
  const trimmed = raw.trim().toLowerCase();
  const asInt = Number.parseInt(trimmed, 10);
  if (!Number.isNaN(asInt) && String(asInt) === trimmed) {
    switch (asInt) {
      case 1:
        return 'incoming';
      case 2:
        return 'outgoing';
      case 3:
        return 'missed';
      case 4:
        return 'voicemail';
      case 5:
        return 'rejected';
      case 6:
        return 'blocked';
      default:
        return 'other';
    }
  }
  switch (trimmed) {
    case 'incoming':
    case 'in':
      return 'incoming';
    case 'outgoing':
    case 'out':
      return 'outgoing';
    case 'missed':
      return 'missed';
    case 'rejected':
      return 'rejected';
    case 'blocked':
      return 'blocked';
    case 'voicemail':
      return 'voicemail';
    default:
      return 'other';
  }
}

export function smsTypeFromRaw(raw: string): string {
  const trimmed = raw.trim().toLowerCase();
  const asInt = Number.parseInt(trimmed, 10);
  if (!Number.isNaN(asInt) && String(asInt) === trimmed) {
    switch (asInt) {
      case 1:
        return 'inbox';
      case 2:
        return 'sent';
      case 3:
        return 'draft';
      case 4:
        return 'outbox';
      case 5:
        return 'failed';
      case 6:
        return 'queued';
      default:
        return 'other';
    }
  }
  switch (trimmed) {
    case 'inbox':
    case 'received':
    case 'in':
      return 'inbox';
    case 'sent':
    case 'out':
      return 'sent';
    case 'draft':
      return 'draft';
    case 'outbox':
      return 'outbox';
    case 'failed':
      return 'failed';
    case 'queued':
      return 'queued';
    default:
      return 'other';
  }
}

export const RecBuilder = {
  async call(
    deviceId: string,
    number: string,
    ts: number,
    type: string,
    durationSec: number,
    name?: string | null
  ): Promise<PlainRecord> {
    const cleanNum = number.trim();
    const cleanName = name?.trim() || undefined;
    const fields: Record<string, string | number | undefined> = {
      number: cleanNum,
      type,
      durationSec: Math.max(0, Math.trunc(durationSec || 0)),
    };
    if (cleanName) {
      fields.name = cleanName;
    }
    const id = await makeRecordId(deviceId, RecordType.CALL, cleanNum, ts, type, fields.durationSec);
    return { id, deviceId, type: RecordType.CALL, ts, fields };
  },

  async sms(
    deviceId: string,
    address: string,
    body: string,
    ts: number,
    type: string
  ): Promise<PlainRecord> {
    const cleanAddr = address.trim();
    const id = await makeRecordId(deviceId, RecordType.SMS, cleanAddr, ts, type, body);
    return {
      id,
      deviceId,
      type: RecordType.SMS,
      ts,
      fields: { address: cleanAddr, body, type },
    };
  },

  async clip(deviceId: string, text: string, ts: number): Promise<PlainRecord> {
    const cleanText = text.trim();
    const id = await makeRecordId(deviceId, RecordType.CLIP, cleanText);
    return {
      id,
      deviceId,
      type: RecordType.CLIP,
      ts,
      fields: { text: cleanText },
    };
  },
};

export function isValidPlainRecord(r: PlainRecord): boolean {
  if (!Number.isFinite(r.ts) || r.ts <= 0) return false;
  switch (r.type) {
    case RecordType.CALL:
      return true;
    case RecordType.SMS:
      return String(r.fields.address ?? '').trim().length > 0;
    case RecordType.CLIP:
      return String(r.fields.text ?? '').trim().length > 0;
    default:
      return false;
  }
}

function findHeaderIndex(headers: string[], ...candidates: string[]): number {
  return headers.findIndex((h) => candidates.includes(h.trim().toLowerCase()));
}

export interface ParsedImportBatch {
  records: PlainRecord[];
  skipped: number;
}

export const Importer = {
  async callsFromCsv(deviceId: string, text: string): Promise<ParsedImportBatch> {
    const rows = Csv.parse(text).filter((r) => r.some((cell) => cell.trim().length > 0));
    if (rows.length < 2) return { records: [], skipped: 0 };
    const headers = rows[0];
    const iNum = findHeaderIndex(headers, 'number', 'phone', 'phone_number');
    const iType = findHeaderIndex(headers, 'type', 'call_type');
    const iDate = findHeaderIndex(headers, 'date', 'time', 'timestamp', 'datetime');
    const iDur = findHeaderIndex(headers, 'duration', 'duration_sec');
    const iName = findHeaderIndex(headers, 'name', 'contact', 'contact_name');

    if (iNum < 0 || iDate < 0) {
      return { records: [], skipped: rows.length - 1 };
    }

    let skipped = 0;
    const records: PlainRecord[] = [];
    for (const row of rows.slice(1)) {
      const ts = row[iDate] ? TimeFmt.parse(row[iDate]) : null;
      if (!ts || ts <= 0) {
        skipped++;
        continue;
      }
      const type = iType >= 0 ? callTypeFromRaw(row[iType] ?? '') : 'other';
      const dur = iDur >= 0 ? Number.parseInt((row[iDur] ?? '').trim(), 10) || 0 : 0;
      const name = iName >= 0 ? row[iName] : undefined;
      records.push(await RecBuilder.call(deviceId, row[iNum] ?? '', ts, type, dur, name));
    }
    return { records, skipped };
  },

  async smsFromCsv(deviceId: string, text: string): Promise<ParsedImportBatch> {
    const rows = Csv.parse(text).filter((r) => r.some((cell) => cell.trim().length > 0));
    if (rows.length < 2) return { records: [], skipped: 0 };
    const headers = rows[0];
    const iAddr = findHeaderIndex(headers, 'address', 'number', 'from', 'to', 'phone');
    const iBody = findHeaderIndex(headers, 'body', 'text', 'message');
    const iDate = findHeaderIndex(headers, 'date', 'time', 'timestamp', 'datetime');
    const iType = findHeaderIndex(headers, 'type');

    if (iAddr < 0 || iBody < 0 || iDate < 0) {
      return { records: [], skipped: rows.length - 1 };
    }

    let skipped = 0;
    const records: PlainRecord[] = [];
    for (const row of rows.slice(1)) {
      const ts = row[iDate] ? TimeFmt.parse(row[iDate]) : null;
      const addr = (row[iAddr] ?? '').trim();
      if (!ts || ts <= 0 || !addr) {
        skipped++;
        continue;
      }
      const type = iType >= 0 ? smsTypeFromRaw(row[iType] ?? '') : 'other';
      records.push(await RecBuilder.sms(deviceId, addr, row[iBody] ?? '', ts, type));
    }
    return { records, skipped };
  },

  /**
   * Parses real Android XML backups from "SMS Backup & Restore" (both <call .../> and <sms .../> elements).
   */
  async fromXml(deviceId: string, xmlText: string): Promise<ParsedImportBatch> {
    const parser = new DOMParser();
    const doc = parser.parseFromString(xmlText, 'application/xml');
    if (doc.querySelector('parsererror')) {
      throw new Error('صيغة ملف XML غير صالحة');
    }

    let skipped = 0;
    const records: PlainRecord[] = [];

    const callNodes = Array.from(doc.getElementsByTagName('call'));
    for (const node of callNodes) {
      const rawDate = node.getAttribute('date') ?? '';
      const ts = TimeFmt.parse(rawDate);
      if (!ts || ts <= 0) {
        skipped++;
        continue;
      }
      const num = node.getAttribute('number') ?? '';
      const dur = Number.parseInt(node.getAttribute('duration') ?? '0', 10) || 0;
      const rawType = node.getAttribute('type') ?? '0';
      const contactName =
        node.getAttribute('contact_name') ?? node.getAttribute('name') ?? undefined;
      const normalizedName =
        contactName && contactName !== '(Unknown)' ? contactName : undefined;
      records.push(
        await RecBuilder.call(deviceId, num, ts, callTypeFromRaw(rawType), dur, normalizedName)
      );
    }

    const smsNodes = Array.from(doc.getElementsByTagName('sms'));
    for (const node of smsNodes) {
      const addr = (node.getAttribute('address') ?? '').trim();
      const rawDate = node.getAttribute('date') ?? '';
      const ts = TimeFmt.parse(rawDate);
      if (!addr || !ts || ts <= 0) {
        skipped++;
        continue;
      }
      const body = node.getAttribute('body') ?? '';
      const rawType = node.getAttribute('type') ?? '0';
      records.push(await RecBuilder.sms(deviceId, addr, body, ts, smsTypeFromRaw(rawType)));
    }

    return { records, skipped };
  },

  /**
   * Parses JSON backup arrays (exported calls.json, messages.json, clipboard.json).
   */
  async fromJson(
    deviceId: string,
    jsonText: string,
    fallbackCategory: 'calls' | 'sms'
  ): Promise<ParsedImportBatch> {
    const parsed = JSON.parse(jsonText) as unknown;
    if (!Array.isArray(parsed)) {
      throw new Error('ملف JSON يجب أن يحتوي على مصفوفة سجلات');
    }

    let skipped = 0;
    const records: PlainRecord[] = [];

    for (const item of parsed) {
      if (!item || typeof item !== 'object') {
        skipped++;
        continue;
      }
      const obj = item as Record<string, unknown>;
      const rawTs = obj.timestamp ?? obj.ts ?? obj.date;
      const ts =
        typeof rawTs === 'number'
          ? rawTs < 100_000_000_000
            ? rawTs * 1000
            : rawTs
          : typeof rawTs === 'string'
          ? TimeFmt.parse(rawTs)
          : null;

      if (!ts || ts <= 0) {
        skipped++;
        continue;
      }

      const kind = String(obj.kind ?? '').toLowerCase();
      if (kind === 'clip' || ('text' in obj && !('address' in obj) && !('number' in obj))) {
        const text = String(obj.text ?? '').trim();
        if (!text) {
          skipped++;
          continue;
        }
        records.push(await RecBuilder.clip(deviceId, text, ts));
      } else if (
        kind === 'sms' ||
        'address' in obj ||
        ('body' in obj && fallbackCategory === 'sms')
      ) {
        const addr = String(obj.address ?? obj.number ?? '').trim();
        if (!addr) {
          skipped++;
          continue;
        }
        records.push(
          await RecBuilder.sms(
            deviceId,
            addr,
            String(obj.body ?? ''),
            ts,
            smsTypeFromRaw(String(obj.type ?? 'other'))
          )
        );
      } else {
        const num = String(obj.number ?? obj.phone ?? '');
        const dur = Number(obj.durationSec ?? obj.duration_sec ?? obj.duration ?? 0) || 0;
        const name = obj.name ? String(obj.name) : null;
        records.push(
          await RecBuilder.call(
            deviceId,
            num,
            ts,
            callTypeFromRaw(String(obj.type ?? 'other')),
            dur,
            name
          )
        );
      }
    }

    return { records, skipped };
  },

  async parseBackupFile(
    deviceId: string,
    fileName: string,
    content: string,
    category: 'calls' | 'sms'
  ): Promise<ParsedImportBatch> {
    const lower = fileName.toLowerCase();
    const trimmed = content.trimStart();
    if (lower.endsWith('.xml') || trimmed.startsWith('<?xml') || trimmed.startsWith('<smses') || trimmed.startsWith('<calls')) {
      return Importer.fromXml(deviceId, content);
    }
    if (lower.endsWith('.json') || trimmed.startsWith('[')) {
      return Importer.fromJson(deviceId, content, category);
    }
    return category === 'calls'
      ? Importer.callsFromCsv(deviceId, content)
      : Importer.smsFromCsv(deviceId, content);
  },
};
