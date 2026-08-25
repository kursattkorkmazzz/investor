package com.investor.llm;

/**
 * Dil modeline erişim portu.
 *
 * <p>Tek metot, kasten. Akış (streaming), araç çağırma (tool calling) ve konuşma hafızası
 * bilinçli olarak <em>yok</em>: bu sistemde modelin işi tek seferlik bir çıkarım yapmak.
 * Araç çağırma eklenirse modele dolaylı bir eylem kanalı açılır ve "LLM emir gönderemez"
 * güvencesi ilk enjeksiyonda düşer. Hafıza eklenirse bir çağrının çıktısı sonrakini
 * kirletir ve her kararın kanıtı yeniden üretilebilir olmaktan çıkar.
 *
 * @see LlmCall
 */
public interface LlmClient {

    /**
     * Çağrıyı yapar, cevabı şemaya göre doğrular.
     *
     * @throws LlmException çağrı başarısız olduğunda ya da cevap şemaya uymadığında
     */
    LlmResult complete(LlmCall call);

    /** Kullanılan modelin kimliği — ontolojide köken olarak kaydedilir. */
    String modelId();
}
