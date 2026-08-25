package com.investor.ontology.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Bir yazma işleminin kim tarafından, neden ve hangi kaynaktan yapıldığı.
 *
 * <p>Ontolojideki her satır bir commit'e bağlıdır; commit'siz veri yazılamaz.
 * Bu, "bu değeri nereden biliyoruz" sorusunun her zaman cevaplanabilir olmasını sağlar.
 *
 * @param commitId   doluysa yazmalar bu mevcut commit'e eklenir; boşsa her çağrı kendi
 *                   commit'ini açar. Birden çok yazmayı tek atomik değişiklik olarak
 *                   kaydetmek için {@link com.investor.ontology.OntologyStore#openCommit}
 *                   kullanılır.
 * @param recordedAt <b>Yalnızca geriye dönük yükleme için.</b> Doluysa kayıt zamanı olarak
 *                   bu kullanılır; boşsa sistem saati. Geçmiş veriyi yüklerken bilginin
 *                   dünyaya <em>gerçekten</em> açıldığı an budur — örneğin bir makro
 *                   rakamın yayın tarihi.
 *                   <p>Bu alan tarih uydurmaya da izin verir: sahip olmadığımız bir bilgiyi
 *                   erken biliyormuş gibi göstermek, backtest'i sistematik olarak iyimser
 *                   yapar. Denetlenebilir kalması için {@code ontology_commit.created_at}
 *                   her zaman gerçek yazma anını tutar; iki zaman arasındaki fark
 *                   sorgulanabilir.
 */
public record CommitContext(
        ActorType actorType,
        String actorId,
        String reason,
        UUID decisionId,
        UUID dataSourceId,
        BigDecimal confidence,
        UUID commitId,
        java.time.Instant recordedAt) {

    public CommitContext {
        if (actorType == null) {
            throw new IllegalArgumentException("actorType zorunlu");
        }
        if (actorId == null || actorId.isBlank()) {
            throw new IllegalArgumentException("actorId zorunlu");
        }
    }

    public static CommitContext system(String actorId, String reason) {
        return new CommitContext(ActorType.SYSTEM, actorId, reason, null, null, null, null, null);
    }

    public static CommitContext human(String actorId, String reason) {
        return new CommitContext(ActorType.HUMAN, actorId, reason, null, null, null, null, null);
    }

    public static CommitContext ingestor(String actorId, String reason, UUID dataSourceId) {
        return new CommitContext(ActorType.INGESTOR, actorId, reason, null, dataSourceId, null, null, null);
    }

    public static CommitContext llmAgent(String agentName, String reason, UUID decisionId, BigDecimal confidence) {
        return new CommitContext(ActorType.LLM_AGENT, agentName, reason, decisionId, null, confidence, null, null);
    }

    public CommitContext withCommitId(UUID id) {
        return new CommitContext(actorType, actorId, reason, decisionId, dataSourceId, confidence,
                id, recordedAt);
    }

    public CommitContext withReason(String newReason) {
        return new CommitContext(actorType, actorId, newReason, decisionId, dataSourceId, confidence,
                commitId, recordedAt);
    }

    public CommitContext withSource(UUID sourceId) {
        return new CommitContext(actorType, actorId, reason, decisionId, sourceId, confidence,
                commitId, recordedAt);
    }

    /**
     * Kayıt zamanını geçersiz kılar — yalnızca geriye dönük yükleme için.
     *
     * <p>Bkz. {@link #recordedAt()}: bu, bilginin ne zaman bilinebilir olduğunu beyan eder.
     * Yanlış kullanıldığında geçmişi olduğundan bilgili gösterir.
     */
    public CommitContext withRecordedAt(java.time.Instant instant) {
        return new CommitContext(actorType, actorId, reason, decisionId, dataSourceId, confidence,
                commitId, instant);
    }
}
