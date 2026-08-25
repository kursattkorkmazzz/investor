/**
 * Analiz katmanı: deterministik hesap ve LLM yorumu.
 *
 * <p>Bu modülün tek bir bölünme kuralı var ve pazarlık konusu değil: <b>hesap
 * deterministik Java'da, yorum LLM'de.</b> RSI'yi ta4j hesaplar, LLM yorumlar. Sebep,
 * dil modellerinin aritmetikte güvenilir olmaması değil sadece — yanlış hesabı son
 * derece ikna edici bir gerekçeyle sunmaları. Bir kez yanlış hesaplanmış indikatör tüm
 * karar zincirini sessizce zehirler.
 *
 * <p>LLM'e verilen her sayı, kaynağı ve nasıl hesaplandığıyla birlikte verilir. LLM'den
 * sayı <em>üretmesi</em> istenmez; yalnızca verilen sayılara referans vermesi istenir.
 *
 * <p>Bkz. {@code docs/05-analiz-ajanlari.md}.
 */
package com.investor.analysis;
