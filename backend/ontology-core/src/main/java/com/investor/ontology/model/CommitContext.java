package com.investor.ontology.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bir yazma işleminin kim tarafından, neden ve hangi kaynaktan yapıldığı.
 *
 * <p>Ontolojideki her satır bir commit'e bağlıdır; commit'siz veri yazılamaz.
 * Bu, "bu değeri nereden biliyoruz" sorusunun her zaman cevaplanabilir olmasını sağlar.
 *
 * @param commitId  doluysa yazmalar bu mevcut commit'e eklenir; boşsa her çağrı kendi
 *                  commit'ini açar. Birden çok yazmayı tek atomik değişiklik olarak
 *                  kaydetmek için {@link com.investor.ontology.OntologyStore#openCommit}
 *                  kullanılır.
 */
public record CommitContext(
        ActorType actorType,
        String actorId,
        String reason,
        UUID decisionId,
        UUID dataSourceId,
        BigDecimal confidence,
        UUID commitId) {

    public CommitContext {
        if (actorType == null) {
            throw new IllegalArgumentException("actorType zorunlu");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId zorunlu");
        }
    }

    public static CommitContext system(String actorId, String reason) {
        return new CommitContext(ActorType.SYSTEM, actorId, reason, null, null, null, null);
    }

    public static CommitContext human(String actorId, String reason) {
        return new CommitContext(ActorType.HUMAN, actorId, reason, null, null, null, null);
    }

    public static CommitContext ingestor(String actorId, String reason, UUID dataSourceId) {
        return new CommitContext(ActorType.INGESTOR, actorId, reason, null, dataSourceId, null, null);
    }

    public static CommitContext llmAgent(String agentName, String reason, UUID decisionId, BigDecimal confidence) {
        return new CommitContext(ActorType.LLM_AGENT, agentName, reason, decisionId, null, confidence, null);
    }

    public CommitContext withCommitId(UUID id) {
        return new CommitContext(actorType, actorId, reason, decisionId, dataSourceId, confidence, id);
    }

    public CommitContext withReason(String newReason) {
        return new CommitContext(actorType, actorId, newReason, decisionId, dataSourceId, confidence, commitId);
    }

    public CommitContext withSource(UUID sourceId) {
        return new CommitContext(actorType, actorId, reason, decisionId, sourceId, confidence, commitId);
    }
}
