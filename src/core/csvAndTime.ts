/**
 * RFC-4180 CSV Parser & Serializer + Timestamp Formatter (Port of Csv.kt & TimeFmt.kt)
 */
export const Csv = {
  escape(cell: string): string {
    if (cell.includes(',') || cell.includes('"') || cell.includes('\n') || cell.includes('\r')) {
      return `"${cell.replace(/"/g, '""')}"`;
    }
    return cell;
  },

  row(cells: string[]): string {
    return cells.map((c) => Csv.escape(c)).join(',');
  },

  parse(rawText: string): string[][] {
    const text = rawText.startsWith('\uFEFF') ? rawText.slice(1) : rawText;
    const rows: string[][] = [];
    let currentRow: string[] = [];
    let cell = '';
    let inQuotes = false;

    for (let i = 0; i < text.length; i++) {
      const ch = text[i];
      if (inQuotes) {
        if (ch === '"') {
          if (i + 1 < text.length && text[i + 1] === '"') {
            cell += '"';
            i++;
          } else {
            inQuotes = false;
          }
        } else {
          cell += ch;
        }
      } else {
        if (ch === '"') {
          inQuotes = true;
        } else if (ch === ',') {
          currentRow.push(cell);
          cell = '';
        } else if (ch === '\r') {
          // Ignore CR
        } else if (ch === '\n') {
          currentRow.push(cell);
          cell = '';
          rows.push(currentRow);
          currentRow = [];
        } else {
          cell += ch;
        }
      }
    }

    if (cell.length > 0 || currentRow.length > 0) {
      currentRow.push(cell);
      rows.push(currentRow);
    }

    return rows;
  },
};

export const TimeFmt = {
  iso(ts: number): string {
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return new Date(0).toISOString();
    const pad = (n: number, len = 2) => String(Math.trunc(Math.abs(n))).padStart(len, '0');
    const offsetMin = -d.getTimezoneOffset();
    const sign = offsetMin >= 0 ? '+' : '-';
    const hh = pad(Math.floor(Math.abs(offsetMin) / 60));
    const mm = pad(Math.abs(offsetMin) % 60);
    const ms = d.getMilliseconds();
    const msPart = ms > 0 ? `.${pad(ms, 3)}` : '';
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
      d.getMinutes()
    )}:${pad(d.getSeconds())}${msPart}${sign}${hh}:${mm}`;
  },

  human(ts: number | null | undefined): string {
    if (!ts || ts <= 0) return '—';
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return '—';
    const pad = (n: number) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(
      d.getMinutes()
    )}`;
  },

  parse(raw: string): number | null {
    const trimmed = raw.trim();
    if (!trimmed) return null;
    if (/^\d+$/.test(trimmed)) {
      const num = Number(trimmed);
      if (!Number.isFinite(num) || num <= 0) return null;
      return num < 100_000_000_000 ? num * 1000 : num;
    }
    const normalized = trimmed.includes(' ') && !trimmed.includes('T') ? trimmed.replace(' ', 'T') : trimmed;
    const parsed = Date.parse(normalized);
    return Number.isNaN(parsed) || parsed <= 0 ? null : parsed;
  },
};
