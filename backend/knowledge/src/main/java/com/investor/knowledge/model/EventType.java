package com.investor.knowledge.model;

/**
 * Haberin olay türü.
 *
 * <p>Kapalı bir küme: analiz ajanları bu etiketlere göre ağırlıklandırma yapacak ve
 * serbest metin bir etiket, kanıt etkinliği ölçümünü imkânsız kılardı.
 */
public enum EventType {
    REGULATORY,
    MACRO,
    PROTOCOL,
    SECURITY_INCIDENT,
    LISTING,
    PARTNERSHIP,
    FUNDING,
    MARKET_MOVE,
    OPINION,
    OTHER
}
