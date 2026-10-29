import JSZip from 'jszip';
import { decryptRecord, sha256Hex } from './crypto';
import { Csv, TimeFmt } from './csvAndTime';
import { EncRecord, ExportFormat, ExportOutcome, ExportRequest, PlainRecord, RecordType } from './types';

const encoder = new TextEncoder();

export const ExportFormats = {
  baseName(type: RecordType): string {
    switch (type) {
      case RecordType.CALL:
        return 'calls';
      case RecordType.SMS:
        return 'messages';
      case RecordType.CLIP:
        return 'clipboard';
    }
  },

  header(type: RecordType): string[] {
    switch (type) {
      case RecordType.CALL:
        return ['id', 'date', 'timestamp', 'number', 'type', 'duration_sec', 'name'];
      case RecordType.SMS:
        return ['id', 'date', 'timestamp', 'address', 'type', 'body'];
      case RecordType.CLIP:
        return ['id', 'date', 'timestamp', 'text'];
    }
  },

  cells(r: PlainRecord): string[] {
    const f = r.fields;
    const base = [r.id, TimeFmt.iso(r.ts), String(r.ts)];
    switch (r.type) {
      case RecordType.CALL:
        return [
          ...base,
          String(f.number ?? ''),
          String(f.type ?? ''),
          String(Number(f.durationSec ?? 0)),
          String(f.name ?? ''),
        ];
      case RecordType.SMS:
        return [...base, String(f.address ?? ''), String(f.type ?? ''), String(f.body ?? '')];
      case RecordType.CLIP:
        return [...base, String(f.text ?? '')];
    }
  },

  csv(type: RecordType, records: PlainRecord[]): string {
    const lines = [Csv.row(ExportFormats.header(type)), ...records.map((r) => Csv.row(ExportFormats.cells(r)))];
    return '\uFEFF' + lines.join('\r\n') + '\r\n';
  },

  json(records: PlainRecord[]): string {
    const lines = records.map((r) => {
      const obj: Record<string, unknown> = {
        id: r.id,
        date: TimeFmt.iso(r.ts),
        timestamp: r.ts,
        kind: r.type,
        ...r.fields,
      };
      return JSON.stringify(obj);
    });
    return `[\n${lines.join(',\n')}\n]\n`;
  },

  txt(type: RecordType, records: PlainRecord[]): string {
    if (records.length === 0) return '\n';
    return (
      records
        .map((r) => {
          const d = TimeFmt.iso(r.ts);
          const f = r.fields;
          switch (type) {
            case RecordType.CALL: {
              const who = String(f.name || '').trim() || String(f.number || '');
              return `[${d}] ${who} | ${String(f.type ?? '')} | ${Number(f.durationSec ?? 0)}s`;
            }
            case RecordType.SMS:
              return `[${d}] ${String(f.address ?? '')} (${String(f.type ?? '')})\n${String(f.body ?? '')}`;
            case RecordType.CLIP:
              return `[${d}]\n${String(f.text ?? '')}`;
          }
        })
        .join('\n\n') + '\n'
    );
  },

  render(format: ExportFormat, type: RecordType, records: PlainRecord[]): Uint8Array {
    switch (format) {
      case 'csv':
        return encoder.encode(ExportFormats.csv(type, records));
      case 'json':
        return encoder.encode(ExportFormats.json(records));
      case 'txt':
        return encoder.encode(ExportFormats.txt(type, records));
    }
  },
};

export function triggerBrowserDownload(bytes: Uint8Array, fileName: string, mimeType: string): void {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  const blob = new Blob([copy], { type: mimeType });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
  setTimeout(() => URL.revokeObjectURL(url), 5000);
}

/**
 * Decrypts encrypted records, formats requested files, optionally bundles a ZIP with manifest.json,
 * and RE-READS the output archive/bytes comparing SHA-256 digests of every entry before declaring success.
 */
export async function runVerifiedExport(
  req: ExportRequest,
  key: CryptoKey,
  encryptedRecords: EncRecord[],
  onProgress: (done: number, total: number) => void
): Promise<ExportOutcome> {
  const filtered = encryptedRecords.filter((r) => {
    if (!req.types.includes(r.type)) return false;
    if (req.from != null && r.ts < req.from) return false;
    if (req.to != null && r.ts >= req.to) return false;
    return true;
  });

  const total = filtered.length;
  onProgress(0, total);

  const expectedShas: Record<string, string> = {};
  const counts: Record<string, number> = {};
  const mustZip = req.types.length * req.formats.length > 1;
  const useZip = req.zip || mustZip;
  const zipBuilder = useZip ? new JSZip() : null;
  let singleFileBytes: Uint8Array | null = null;
  let singleFileName = '';
  let done = 0;

  for (const type of req.types) {
    const encForType = filtered.filter((r) => r.type === type).sort((a, b) => a.ts - b.ts);
    const decrypted: PlainRecord[] = [];
    for (const enc of encForType) {
      try {
        decrypted.push(await decryptRecord(key, req.deviceId, enc));
      } catch {
        throw new Error('فشل فك تشفير أحد السجلات؛ تحقق من عبارة المرور أو سلامة البيانات.');
      }
      done++;
      if (done % 25 === 0 || done === total) {
        onProgress(done, total);
      }
    }
    counts[type] = decrypted.length;

    for (const fmt of req.formats) {
      const fileBytes = ExportFormats.render(fmt, type, decrypted);
      const entryName = `${ExportFormats.baseName(type)}.${fmt}`;
      expectedShas[entryName] = await sha256Hex(fileBytes);
      if (zipBuilder) {
        zipBuilder.file(entryName, fileBytes);
      } else {
        singleFileBytes = fileBytes;
        singleFileName = entryName;
      }
    }
  }

  let outputBytes: Uint8Array;
  let outputFileName: string;
  let mimeType: string;

  const stamp = (() => {
    const d = new Date();
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}-${pad(d.getHours())}${pad(
      d.getMinutes()
    )}`;
  })();
  const safeDevice = req.deviceName.replace(/[^\p{L}\p{N}]+/gu, '_').replace(/^_+|_+$/g, '') || req.deviceId;

  if (zipBuilder) {
    const manifestObj = {
      deviceId: req.deviceId,
      deviceName: req.deviceName,
      exportedAt: new Date().toISOString(),
      counts,
      from: req.from,
      to: req.to,
    };
    const manifestBytes = encoder.encode(JSON.stringify(manifestObj, null, 2));
    expectedShas['manifest.json'] = await sha256Hex(manifestBytes);
    zipBuilder.file('manifest.json', manifestBytes);

    outputBytes = await zipBuilder.generateAsync({
      type: 'uint8array',
      compression: 'DEFLATE',
      compressionOptions: { level: 6 },
    });
    outputFileName = `backup-${safeDevice}-${stamp}.zip`;
    mimeType = 'application/zip';

    // Read-back verification: re-parse generated ZIP archive from raw bytes and compare every SHA-256 hash
    const readBackZip = await JSZip.loadAsync(outputBytes);
    const actualShas: Record<string, string> = {};
    for (const [entryName, zipEntry] of Object.entries(readBackZip.files)) {
      if (zipEntry.dir) continue;
      const entryBytes = await zipEntry.async('uint8array');
      actualShas[entryName] = await sha256Hex(entryBytes);
    }

    const expectedKeys = Object.keys(expectedShas).sort();
    const actualKeys = Object.keys(actualShas).sort();
    if (
      expectedKeys.length !== actualKeys.length ||
      expectedKeys.some((k, idx) => k !== actualKeys[idx] || expectedShas[k] !== actualShas[k])
    ) {
      throw new Error('فشل التحقق الرقمي (SHA-256): الأرشيف المُنشأ لا يطابق البيانات المسترجعة.');
    }
  } else {
    if (!singleFileBytes) {
      throw new Error('لم يتم اختيار أي نوع أو صيغة للتصدير.');
    }
    outputBytes = singleFileBytes;
    outputFileName = singleFileName;
    mimeType = singleFileName.endsWith('.json')
      ? 'application/json;charset=utf-8'
      : singleFileName.endsWith('.csv')
      ? 'text/csv;charset=utf-8'
      : 'text/plain;charset=utf-8';

    // Read-back verification on single output buffer
    const readBackHash = await sha256Hex(new Uint8Array(outputBytes));
    if (readBackHash !== expectedShas[singleFileName]) {
      throw new Error('فشل التحقق الرقمي (SHA-256): الملف المُنشأ لا يطابق البيانات المسترجعة.');
    }
  }

  triggerBrowserDownload(outputBytes, outputFileName, mimeType);

  return {
    fileName: outputFileName,
    recordCount: done,
    fileCount: Object.keys(expectedShas).length,
    verifiedHashes: expectedShas,
  };
}
