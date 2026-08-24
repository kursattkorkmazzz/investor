import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { api } from '@/api/client';
import { ErrorNote, Empty } from '@/components/ui';
import { ObjectDetail } from '@/features/ontology/ObjectDetail';
import { ObjectList } from '@/features/ontology/ObjectList';
import { TypeList } from '@/features/ontology/TypeList';

/**
 * Ontology Explorer.
 *
 * Faz-1'in arayüz karşılığı: şema çalışma zamanında okunuyor, nesneler dinamik sorguyla
 * geliyor, her alanın geçmişi ve "o gün ne biliyorduk" görünümü açılabiliyor.
 */
export default function App() {
  const [selectedType, setSelectedType] = useState<string | null>(null);
  const [selectedObject, setSelectedObject] = useState<string | null>(null);
  const [asOf, setAsOf] = useState<string | null>(null);

  const { data: types, isPending, error } = useQuery({
    queryKey: ['types'],
    queryFn: api.types,
  });

  const activeType = types?.find((type) => type.apiName === selectedType) ?? null;

  return (
    <div className="flex h-screen flex-col gap-3 p-3">
      <header className="flex items-baseline gap-3">
        <h1 className="text-base font-semibold text-ink">investor</h1>
        <span className="text-xs text-ink-muted">Ontology Explorer</span>
      </header>

      {error && <ErrorNote error={error} />}
      {isPending && !error && <Empty>şema yükleniyor…</Empty>}

      {types && (
        <main className="grid min-h-0 flex-1 grid-cols-[minmax(200px,240px)_minmax(200px,280px)_1fr] gap-3">
          <TypeList
            types={types}
            selected={selectedType}
            onSelect={(apiName) => {
              setSelectedType(apiName);
              setSelectedObject(null);
            }}
          />

          {activeType ? (
            <ObjectList
              type={activeType}
              asOf={asOf}
              selectedId={selectedObject}
              onSelect={setSelectedObject}
            />
          ) : (
            <div className="rounded-lg border border-border-subtle bg-surface-raised">
              <Empty>Soldan bir tip seçin.</Empty>
            </div>
          )}

          {activeType && selectedObject ? (
            <ObjectDetail
              objectId={selectedObject}
              type={activeType}
              asOf={asOf}
              onAsOfChange={setAsOf}
            />
          ) : (
            <div className="rounded-lg border border-border-subtle bg-surface-raised">
              <Empty>Bir nesne seçin.</Empty>
            </div>
          )}
        </main>
      )}
    </div>
  );
}
