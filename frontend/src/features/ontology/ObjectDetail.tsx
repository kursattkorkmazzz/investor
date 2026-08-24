import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import type { ObjectType, PropertyType } from '@/api/types';
import { Badge, Empty, ErrorNote, Panel } from '@/components/ui';
import { exactValue, formatValue } from '@/lib/format';
import { AsOfControl } from './AsOfControl';
import { PropertyHistory } from './PropertyHistory';

export function ObjectDetail({ objectId, type, asOf, onAsOfChange }: {
  objectId: string;
  type: ObjectType;
  asOf: string | null;
  onAsOfChange: (next: string | null) => void;
}) {
  const [openProperty, setOpenProperty] = useState<string | null>(null);

  const { data, isPending, error } = useQuery({
    queryKey: ['object', objectId, asOf],
    queryFn: () => api.object(objectId, asOf ?? undefined),
  });

  const properties = visibleProperties(type);

  return (
    <Panel
      title={data?.title ?? 'Nesne'}
      actions={<AsOfControl value={asOf} onChange={onAsOfChange} />}
    >
      {error && <ErrorNote error={error} />}
      {isPending && !error && <Empty>yükleniyor…</Empty>}

      {data && (
        <div className="divide-y divide-border-subtle">
          <div className="px-4 py-3 text-xs text-ink-muted">
            <span className="text-ink">{data.typeApiName}</span>
            <span className="mx-2">·</span>
            {data.externalId}
          </div>

          {properties.length === 0 && <Empty>Bu tipte tanımlı alan yok.</Empty>}

          {properties.map((property) => {
            const value = data.data[property.apiName];
            const exact = exactValue(value, property.dataType);
            const isOpen = openProperty === property.apiName;

            return (
              <div key={property.apiName}>
                <button
                  type="button"
                  onClick={() => setOpenProperty(isOpen ? null : property.apiName)}
                  className="flex w-full items-baseline gap-3 px-4 py-2.5 text-left hover:bg-surface"
                >
                  <span className="w-48 shrink-0 text-xs text-ink-muted">
                    {property.displayName}
                    {property.deprecated && <Badge tone="warn"> kullanımdan kalktı</Badge>}
                  </span>
                  <span className="flex-1 text-sm text-ink" title={exact ?? undefined}>
                    {formatValue(value, property.dataType)}
                    {property.unit && value != null && (
                      <span className="ml-1 text-ink-muted">{property.unit}</span>
                    )}
                  </span>
                  <span className="text-[11px] text-ink-muted">
                    {isOpen ? 'geçmişi gizle' : 'geçmiş'}
                  </span>
                </button>
                {isOpen && (
                  <div className="border-t border-border-subtle bg-surface">
                    <PropertyHistory objectId={objectId} property={property} />
                  </div>
                )}
              </div>
            );
          })}

          {Object.entries(data.links).map(([linkName, links]) => (
            <div key={linkName} className="px-4 py-2.5">
              <div className="text-xs text-ink-muted">{linkName}</div>
              <ul className="mt-1 space-y-1">
                {links.map((link) => (
                  <li key={link.targetObjectId} className="text-sm text-ink">
                    {link.targetTitle ?? link.targetExternalId ?? link.targetObjectId}
                    {link.weight && <Badge> ağırlık {link.weight}</Badge>}
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </Panel>
  );
}

/** Kalıtılan alanlar da gösterilmeli; şu an tip hiyerarşisi tek seviye derinlikte çözülüyor. */
function visibleProperties(type: ObjectType): PropertyType[] {
  return [...type.properties].sort((a, b) => a.displayOrder - b.displayOrder
    || a.apiName.localeCompare(b.apiName));
}
