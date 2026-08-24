import type {
  HistoryEntry, LinkType, ObjectType, OntologyObject, ProblemDetail, QueryRequest, QueryResponse,
} from './types';

/** Sunucunun RFC 7807 problem gövdesini taşıyan hata. */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: ProblemDetail,
  ) {
    super(problem.detail ?? problem.title ?? `HTTP ${status}`);
    this.name = 'ApiError';
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`/api${path}`, {
    ...init,
    headers: { 'Content-Type': 'application/json', ...init?.headers },
  });

  if (!response.ok) {
    let problem: ProblemDetail = { title: `HTTP ${response.status}` };
    try {
      problem = (await response.json()) as ProblemDetail;
    } catch {
      // Gövde yoksa ya da JSON değilse varsayılanla devam et.
    }
    throw new ApiError(response.status, problem);
  }

  return response.status === 204 ? (undefined as T) : ((await response.json()) as T);
}

const asOfParam = (asOf?: string) => (asOf ? `?asOf=${encodeURIComponent(asOf)}` : '');

export const api = {
  types: () => request<ObjectType[]>('/ontology/types'),

  type: (apiName: string) => request<ObjectType>(`/ontology/types/${apiName}`),

  linkTypes: () => request<LinkType[]>('/ontology/link-types'),

  object: (id: string, asOf?: string) =>
    request<OntologyObject>(`/ontology/objects/${id}${asOfParam(asOf)}`),

  history: (id: string, property: string) =>
    request<HistoryEntry[]>(`/ontology/objects/${id}/history/${property}`),

  query: (body: QueryRequest) =>
    request<QueryResponse>('/ontology/query', { method: 'POST', body: JSON.stringify(body) }),
};
