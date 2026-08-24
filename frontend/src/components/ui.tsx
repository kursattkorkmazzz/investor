import type { ReactNode } from 'react';

export function Panel({ title, actions, children }: {
  title: string;
  actions?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="flex min-h-0 flex-col rounded-lg border border-border-subtle bg-surface-raised">
      <header className="flex items-center justify-between gap-2 border-b border-border-subtle px-4 py-2.5">
        <h2 className="text-sm font-medium text-ink">{title}</h2>
        {actions}
      </header>
      <div className="min-h-0 flex-1 overflow-auto">{children}</div>
    </section>
  );
}

export function Empty({ children }: { children: ReactNode }) {
  return <p className="px-4 py-6 text-sm text-ink-muted">{children}</p>;
}

export function ErrorNote({ error }: { error: unknown }) {
  const problem = (error as { problem?: { title?: string; detail?: string; hint?: string } }).problem;
  return (
    <div className="m-4 rounded-md border border-danger/40 bg-danger/10 px-3 py-2 text-sm">
      <p className="font-medium text-ink">{problem?.title ?? 'Hata'}</p>
      <p className="mt-0.5 text-ink-muted">{problem?.detail ?? String(error)}</p>
      {problem?.hint && <p className="mt-1 text-xs text-ink-muted italic">{problem.hint}</p>}
    </div>
  );
}

export function Badge({ tone = 'neutral', children }: {
  tone?: 'neutral' | 'accent' | 'warn' | 'danger';
  children: ReactNode;
}) {
  const tones = {
    neutral: 'border-border-subtle text-ink-muted',
    accent: 'border-accent/50 text-accent',
    warn: 'border-warn/50 text-warn',
    danger: 'border-danger/50 text-danger',
  } as const;
  return (
    <span className={`rounded border px-1.5 py-0.5 text-[11px] leading-none ${tones[tone]}`}>
      {children}
    </span>
  );
}
