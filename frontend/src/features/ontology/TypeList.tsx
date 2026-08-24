import type { ObjectType } from '@/api/types';
import { Badge, Empty, Panel } from '@/components/ui';

export function TypeList({ types, selected, onSelect }: {
  types: ObjectType[];
  selected: string | null;
  onSelect: (apiName: string) => void;
}) {
  return (
    <Panel title="Tipler">
      {types.length === 0 && (
        <Empty>
          Henüz tip tanımlı değil. Ontoloji çalışma zamanında kurulur — şema için deploy
          gerekmez.
        </Empty>
      )}
      <ul>
        {types.map((type) => (
          <li key={type.apiName}>
            <button
              type="button"
              disabled={type.isAbstract}
              onClick={() => onSelect(type.apiName)}
              className={`w-full px-4 py-2 text-left hover:bg-surface disabled:cursor-not-allowed disabled:opacity-50 ${
                selected === type.apiName ? 'bg-surface' : ''
              }`}
            >
              <div className="flex items-center gap-2">
                <span className="text-sm text-ink">{type.displayName}</span>
                {type.isAbstract && <Badge>soyut</Badge>}
              </div>
              <div className="text-[11px] text-ink-muted">
                {type.apiName}
                {type.parentTypeApiName && ` ← ${type.parentTypeApiName}`}
                {` · v${type.currentVersion} · ${type.properties.length} alan`}
              </div>
            </button>
          </li>
        ))}
      </ul>
    </Panel>
  );
}
