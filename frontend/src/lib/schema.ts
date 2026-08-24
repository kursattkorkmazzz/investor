import type { ObjectType, PropertyType } from '@/api/types';

/**
 * Tipin kendi ve kalıtılan tüm alanları.
 *
 * Backend alanları hiyerarşide yukarı doğru çözüyor (bir `CryptoAsset`, `Asset`'in
 * `name` alanını taşır) ama `/types` yanıtı her tipin yalnızca kendi alanlarını döner.
 * Arayüz aynı çözümü yapmazsa kalıtılan alanlar ekranda kaybolur.
 *
 * En türemiş tipte tanımlı olan kazanır: aynı adı alt tip yeniden tanımlamışsa onunki geçerli.
 */
export function resolveProperties(type: ObjectType, allTypes: ObjectType[]): PropertyType[] {
  const byApiName = new Map<string, ObjectType>(allTypes.map((t) => [t.apiName, t]));
  const resolved = new Map<string, PropertyType>();

  let cursor: ObjectType | undefined = type;
  const seen = new Set<string>();
  while (cursor && !seen.has(cursor.apiName)) {
    seen.add(cursor.apiName);
    for (const property of cursor.properties) {
      if (!resolved.has(property.apiName)) resolved.set(property.apiName, property);
    }
    cursor = cursor.parentTypeApiName ? byApiName.get(cursor.parentTypeApiName) : undefined;
  }

  return [...resolved.values()].sort(
    (a, b) => a.displayOrder - b.displayOrder || a.apiName.localeCompare(b.apiName),
  );
}
