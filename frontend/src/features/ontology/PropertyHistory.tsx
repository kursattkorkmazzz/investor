import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import type { PropertyType } from '@/api/types';
import { Empty, ErrorNote, Badge } from '@/components/ui';
import { formatInstant, formatValidTo, formatValue } from '@/lib/format';

/**
 * Bir alanın tam geçmişi.
 *
 * Geri çekilmiş kayıtlar gizlenmez, üstü çizili gösterilir: sistemin bir dönem yanlış
 * bilgiyle çalıştığı denetimde görünür olmalı. "Geçerlilik" ile "ne zaman öğrendik"
 * ayrı sütunlar — ikisini birleştirmek bitemporal modelin bütün anlamını siler.
 */
export function PropertyHistory({ objectId, property }: {
  objectId: string;
  property: PropertyType;
}) {
  const { data, isPending, error } = useQuery({
    queryKey: ['history', objectId, property.apiName],
    queryFn: () => api.history(objectId, property.apiName),
  });

  if (error) return <ErrorNote error={error} />;
  if (isPending) return <Empty>yükleniyor…</Empty>;
  if (data.length === 0) return <Empty>Bu alan için henüz kayıt yok.</Empty>;

  return (
    <table className="w-full text-left text-xs">
      <thead className="sticky top-0 bg-surface-raised text-ink-muted">
        <tr className="border-b border-border-subtle">
          <th className="px-3 py-2 font-medium">Değer</th>
          <th className="px-3 py-2 font-medium">Geçerlilik</th>
          <th className="px-3 py-2 font-medium">Öğrenildi</th>
          <th className="px-3 py-2 font-medium">Kaynak</th>
          <th className="px-3 py-2 font-medium">Gerekçe</th>
        </tr>
      </thead>
      <tbody>
        {data.map((entry) => (
          <tr
            key={entry.valueId}
            className={`border-b border-border-subtle/50 align-top ${
              entry.retractedAt ? 'opacity-55' : ''
            }`}
          >
            <td className="px-3 py-2">
              <span className={entry.retractedAt ? 'line-through' : 'text-ink'}>
                {formatValue(entry.value, property.dataType)}
              </span>
              {property.unit && <span className="ml-1 text-ink-muted">{property.unit}</span>}
              <div className="mt-1 flex gap-1">
                {entry.retractedAt && <Badge tone="danger">geri çekildi</Badge>}
                {!entry.retractedAt && entry.validTo === null && <Badge tone="accent">açık</Badge>}
                {entry.confidence && <Badge>güven {entry.confidence}</Badge>}
              </div>
            </td>
            <td className="px-3 py-2 text-ink-muted">
              {formatInstant(entry.validFrom)}
              <span className="mx-1">→</span>
              {formatValidTo(entry.validTo)}
            </td>
            <td className="px-3 py-2 text-ink-muted">
              {formatInstant(entry.recordedAt)}
              {entry.retractedAt && (
                <div className="text-danger">geri çekme: {formatInstant(entry.retractedAt)}</div>
              )}
            </td>
            <td className="px-3 py-2 text-ink-muted">
              {entry.source ?? '—'}
              <div className="text-[11px]">
                {entry.actorType}
                {entry.actorId ? ` · ${entry.actorId}` : ''}
              </div>
            </td>
            <td className="px-3 py-2 text-ink-muted">{entry.reason ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
