package com.investor.ontology.model;

/**
 * Bir değişikliği kimin yaptığı. Bir kararı denetlerken "bu bilgiyi insan mı girdi,
 * ingest mi getirdi, LLM mi çıkarım yaptı" sorusunun cevabı bu alandır.
 */
public enum ActorType {
    HUMAN,
    INGESTOR,
    LLM_AGENT,
    SYSTEM,
    MIGRATION
}
