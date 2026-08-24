import Decimal from 'decimal.js';
import type { DataType } from '@/api/types';

const numberFormat = new Intl.NumberFormat('tr-TR', { maximumFractionDigits: 8 });
const dateTimeFormat = new Intl.DateTimeFormat('tr-TR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'UTC',
});

/**
 * Alanın şemadaki tipine göre gösterim.
 *
 * Ondalık değerler backend'den string gelir ve burada da `Decimal` üzerinden geçer —
 * `Number()`'a uğrasa 18 ondalıklı token miktarları sessizce bozulurdu.
 */
export function formatValue(value: unknown, dataType: DataType): string {
  if (value === null || value === undefined) return '—';

  if (Array.isArray(value)) {
    return value.map((item) => formatValue(item, dataType)).join(', ');
  }

  switch (dataType) {
    case 'INTEGER':
    case 'DECIMAL':
      return numberFormat.format(new Decimal(String(value)).toNumber());
    case 'TIMESTAMP':
      return dateTimeFormat.format(new Date(String(value)));
    case 'DATE':
      return new Date(String(value)).toISOString().slice(0, 10);
    case 'BOOLEAN':
      return value ? 'evet' : 'hayır';
    case 'JSON':
      return JSON.stringify(value);
    default:
      return String(value);
  }
}

/** Ondalık değerin tam hâli — yuvarlanmadan, kopyalanabilir. */
export function exactValue(value: unknown, dataType: DataType): string | null {
  if (value === null || value === undefined) return null;
  if (dataType !== 'DECIMAL' && dataType !== 'INTEGER') return null;
  return new Decimal(String(value)).toFixed();
}

export function formatInstant(iso: string | null | undefined): string {
  if (!iso) return '—';
  return dateTimeFormat.format(new Date(iso));
}

/** "hâlâ geçerli" durumunu ayrı gösterir; sonsuzu tarih gibi yazmak yanıltıcı. */
export function formatValidTo(iso: string | null): string {
  return iso === null ? 'hâlâ geçerli' : formatInstant(iso);
}
