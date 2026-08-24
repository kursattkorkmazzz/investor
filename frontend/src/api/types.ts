/**
 * Backend sözleşmesi.
 *
 * Ondalık değerler string olarak gelir — JavaScript'in `number`'ı IEEE 754 double ve
 * 18 ondalıklı token miktarlarını sessizce yuvarlar. Alanın gerçek tipi `dataType`
 * meta verisinden bilinir; gösterim ve karşılaştırma `decimal.js` ile yapılır.
 */

export type DataType =
  | 'STRING' | 'TEXT' | 'INTEGER' | 'DECIMAL' | 'BOOLEAN'
  | 'TIMESTAMP' | 'DATE' | 'ENUM' | 'JSON' | 'REFERENCE';

export type Cardinality = 'SINGLE' | 'LIST';

export interface PropertyType {
  id: string;
  apiName: string;
  displayName: string;
  description: string | null;
  dataType: DataType;
  cardinality: Cardinality;
  required: boolean;
  title: boolean;
  unit: string | null;
  constraints: Record<string, unknown>;
  deprecated: boolean;
  displayOrder: number;
}

export interface ObjectType {
  id: string;
  apiName: string;
  displayName: string;
  description: string | null;
  icon: string | null;
  isAbstract: boolean;
  parentTypeApiName: string | null;
  currentVersion: number;
  properties: PropertyType[];
}

export interface LinkType {
  id: string;
  apiName: string;
  displayName: string;
  reverseApiName: string;
  reverseDisplayName: string;
  fromTypeApiName: string;
  toTypeApiName: string;
  cardinality: 'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'MANY_TO_MANY';
  symmetric: boolean;
}

export interface LinkRef {
  linkApiName: string;
  targetObjectId: string;
  targetTypeApiName: string | null;
  targetExternalId: string | null;
  targetTitle: string | null;
  weight: string | null;
  properties: Record<string, unknown>;
  validFrom: string | null;
  validTo: string | null;
}

export interface OntologyObject {
  objectId: string;
  typeApiName: string;
  externalId: string;
  title: string | null;
  /** null ise güncel görünüm; dolu ise "o anda bildiğimiz" hâl. */
  knowledgeTime: string | null;
  data: Record<string, unknown>;
  links: Record<string, LinkRef[]>;
}

/**
 * Bir alanın geçmişindeki tek kayıt.
 *
 * `retractedAt` dolu olanlar listeden çıkarılmaz: sistemin bir dönem yanlış bilgiyle
 * çalıştığı görünür olmalı.
 */
export interface HistoryEntry {
  valueId: number;
  propertyApiName: string;
  ordinal: number;
  value: unknown;
  validFrom: string;
  validTo: string | null;
  recordedAt: string;
  retractedAt: string | null;
  commitId: string;
  actorType: 'HUMAN' | 'INGESTOR' | 'LLM_AGENT' | 'SYSTEM' | 'MIGRATION';
  actorId: string;
  reason: string | null;
  source: string | null;
  confidence: string | null;
}

export interface QueryResponse {
  objects: OntologyObject[];
  total: number;
  hasMore: boolean;
}

export type Operator =
  | 'EQ' | 'NEQ' | 'GT' | 'GTE' | 'LT' | 'LTE'
  | 'IN' | 'CONTAINS' | 'STARTS_WITH' | 'IS_NULL' | 'IS_NOT_NULL';

export interface QueryRequest {
  type: string;
  search?: string;
  where?: { field: string; op: Operator; value?: unknown }[];
  orderBy?: { field: string; direction?: 'ASC' | 'DESC' }[];
  asOf?: string;
  limit?: number;
  offset?: number;
}

export interface ProblemDetail {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  hint?: string;
}
