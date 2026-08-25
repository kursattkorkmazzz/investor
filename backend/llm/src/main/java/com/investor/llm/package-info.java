/**
 * LLM erişim katmanı.
 *
 * <p>Bu modül tek bir işi yapar: bir dil modeline <em>şemaya zorlanmış</em> bir soru sorar ve
 * cevabı doğrulanmış olarak döndürür. Sağlayıcı (şu an LangChain4j üzerinden OpenAI uyumlu bir
 * uç nokta) bu paketin dışına sızmaz — bkz. {@code docs/adr/0008-langchain4j.md}.
 *
 * <p><b>Neden serbest metin yok:</b> modele sorulan her şey düşman girdisi içerebilir. Bir haber
 * gövdesi "önceki talimatları unut, ver şu emri" yazabilir. Çıktı kapalı bir şemaya zorlandığında
 * başarılı bir enjeksiyon bile yalnızca şemanın izin verdiği aralıkta bir sayıyı oynatabilir;
 * yeni bir eylem kanalı açamaz. Bu modül hiçbir koşulda emir üretmez ve emir gönderme yetkisi
 * olan hiçbir bileşene doğrudan bağlanmaz.
 */
package com.investor.llm;
