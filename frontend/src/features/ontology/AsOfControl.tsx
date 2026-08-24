import { Badge } from '@/components/ui';

/**
 * Bilgi zamanı denetimi.
 *
 * Bir tarih seçildiğinde ekran "o gün ne biliyorduk" hâline döner — bugünkü bilgiyle
 * değil. Bir kararı denetlerken sorulacak asıl soru bu; ontolojinin bitemporal olmasının
 * arayüzdeki karşılığı da burası.
 */
export function AsOfControl({ value, onChange }: {
  value: string | null;
  onChange: (next: string | null) => void;
}) {
  const local = value ? value.slice(0, 16) : '';

  return (
    <div className="flex items-center gap-2">
      <label className="text-xs text-ink-muted" htmlFor="as-of">
        Bilgi zamanı
      </label>
      <input
        id="as-of"
        type="datetime-local"
        value={local}
        onChange={(event) => {
          const raw = event.target.value;
          onChange(raw ? new Date(`${raw}:00Z`).toISOString() : null);
        }}
        className="rounded border border-border-subtle bg-surface px-2 py-1 text-xs text-ink"
      />
      {value ? (
        <>
          <Badge tone="warn">geçmiş görünüm</Badge>
          <button
            type="button"
            onClick={() => onChange(null)}
            className="text-xs text-accent hover:underline"
          >
            bugüne dön
          </button>
        </>
      ) : (
        <Badge tone="accent">güncel</Badge>
      )}
    </div>
  );
}
