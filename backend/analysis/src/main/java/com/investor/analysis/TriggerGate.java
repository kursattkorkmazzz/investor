package com.investor.analysis;

import com.investor.analysis.model.TriggerContext;
import com.investor.analysis.model.TriggerDecision;

/**
 * Pahalı LLM turuna gitmeden önceki ucuz, deterministik kontrol.
 *
 * <p><b>Bu arayüz maliyet tasarımının tamamının dayandığı yer.</b> Naif tasarım burada
 * duvara çarpıyor: 8 sembol × 15 dakikada bir tur = günde 768 tur, tur başına ~$0.40 →
 * günde $270. Sürdürülemez.
 *
 * <p>Kapı, tur sayısını hedeflenen ~%5'e indiriyor. Bunun için hiçbir LLM çağrısı
 * yapmıyor: yalnızca zaten hesaplanmış göstergelere, istatistiklere ve rejime bakıyor.
 * Deterministik olduğu için geri testte de bedavaya koşuyor ve aynı geçmiş gün aynı
 * turları açıyor.
 *
 * <p>Kapının kendisi de ölçülecek: hangi tetikleyicinin açtığı turların iyi kararlar
 * ürettiği sonradan sorulacak ve fayda üretmeyen tetikleyici budanacak.
 */
public interface TriggerGate {

    TriggerDecision evaluate(TriggerContext context);
}
