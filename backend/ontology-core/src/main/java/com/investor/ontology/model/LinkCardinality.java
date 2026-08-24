package com.investor.ontology.model;

/**
 * İlişki kardinalitesi. Kaynak (from) ve hedef (to) tarafındaki sınırları ayrı ayrı ifade eder.
 *
 * <p>Örnek: {@code Instrument -TRADES_ON-> Exchange} bir {@link #MANY_TO_ONE}'dır —
 * bir enstrüman tek borsada işlem görür, bir borsada çok enstrüman vardır.
 */
public enum LinkCardinality {

    /** Kaynak en fazla bir hedefe, hedef en fazla bir kaynağa bağlanır. */
    ONE_TO_ONE(true, true),

    /** Kaynak çok hedefe bağlanabilir; hedefin en fazla bir kaynağı olur. */
    ONE_TO_MANY(false, true),

    /** Kaynağın en fazla bir hedefi olur; hedefe çok kaynak bağlanabilir. */
    MANY_TO_ONE(true, false),

    /** Sınır yok. */
    MANY_TO_MANY(false, false);

    private final boolean singleTargetPerSource;
    private final boolean singleSourcePerTarget;

    LinkCardinality(boolean singleTargetPerSource, boolean singleSourcePerTarget) {
        this.singleTargetPerSource = singleTargetPerSource;
        this.singleSourcePerTarget = singleSourcePerTarget;
    }

    /** Yeni bağ kurulurken kaynağın diğer açık bağları kapatılmalı mı? */
    public boolean singleTargetPerSource() {
        return singleTargetPerSource;
    }

    /** Yeni bağ kurulurken hedefe gelen diğer açık bağlar kapatılmalı mı? */
    public boolean singleSourcePerTarget() {
        return singleSourcePerTarget;
    }
}
