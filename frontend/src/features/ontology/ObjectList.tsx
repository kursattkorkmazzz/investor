import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import type { ObjectType } from '@/api/types';
import { Empty, ErrorNote, Panel } from '@/components/ui';

export function ObjectList({ type, asOf, selectedId, onSelect }: {
  type: ObjectType;
  asOf: string | null;
  selectedId: string | null;
  onSelect: (objectId: string) => void;
}) {
  const [search, setSearch] = useState('');

  const { data, isPending, error } = useQuery({
    queryKey: ['objects', type.apiName, search, asOf],
    queryFn: () => api.query({
      type: type.apiName,
      ...(search ? { search } : {}),
      ...(asOf ? { asOf } : {}),
      limit: 100,
    }),
  });

  return (
    <Panel
      title={type.displayName}
      actions={
        <input
          type="search"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
          placeholder="ara…"
          className="w-32 rounded border border-border-subtle bg-surface px-2 py-1 text-xs text-ink"
        />
      }
    >
      {error && <ErrorNote error={error} />}
      {isPending && !error && <Empty>yükleniyor…</Empty>}
      {data?.objects.length === 0 && <Empty>Bu tipte nesne yok.</Empty>}

      <ul>
        {data?.objects.map((object) => (
          <li key={object.objectId}>
            <button
              type="button"
              onClick={() => onSelect(object.objectId)}
              className={`w-full px-4 py-2 text-left hover:bg-surface ${
                selectedId === object.objectId ? 'bg-surface' : ''
              }`}
            >
              <div className="truncate text-sm text-ink">{object.title ?? object.externalId}</div>
              <div className="truncate text-[11px] text-ink-muted">{object.externalId}</div>
            </button>
          </li>
        ))}
      </ul>

      {data && data.hasMore && (
        <p className="px-4 py-2 text-[11px] text-ink-muted">
          {data.total} nesneden ilk {data.objects.length} tanesi gösteriliyor.
        </p>
      )}
    </Panel>
  );
}
