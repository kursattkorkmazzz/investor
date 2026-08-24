package com.investor.ontology;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.investor.ontology.model.CommitContext;
import com.investor.ontology.model.LinkProperties;
import com.investor.ontology.model.LinkTypeDef;
import com.investor.ontology.model.LinkView;
import com.investor.ontology.model.NewLinkType;
import com.investor.ontology.model.NewObjectType;
import com.investor.ontology.model.NewPropertyType;
import com.investor.ontology.model.ObjectRef;
import com.investor.ontology.model.ObjectTypeDef;
import com.investor.ontology.model.ObjectView;
import com.investor.ontology.model.PropertyHistoryEntry;
import com.investor.ontology.model.PropertyTypeDef;
import com.investor.ontology.model.Value;
import com.investor.ontology.query.OntologyQuery;
import com.investor.ontology.query.QueryResult;

/**
 * Ontolojiye tek erişim noktası.
 *
 * <h2>Versiyonlama sözleşmesi</h2>
 * Hiçbir değer ezilmez, hiçbir satır silinmez. Bir alanı güncellemek, mevcut geçerlilik
 * aralığını kapatıp yeni bir satır eklemektir; bir nesneyi silmek onu damgalamaktır.
 * Bu yüzden burada {@code update} veya {@code delete} adında bir metot yoktur.
 *
 * <h2>İki zaman ekseni</h2>
 * <ul>
 *   <li>{@code validFrom} — bilginin gerçek dünyada geçerli olmaya başladığı an</li>
 *   <li>{@code recordedAt} — bizim öğrendiğimiz an (otomatik, {@code Clock}'tan)</li>
 * </ul>
 * {@link #asOf(ObjectRef, Instant)} ikisini birden dikkate alır: "o tarihte bildiğimiz hâl".
 * {@link #current(ObjectRef)} ise bugünkü en iyi bilgimizi verir.
 */
public interface OntologyStore {

    // ------------------------------------------------------------------ meta

    ObjectTypeDef defineObjectType(NewObjectType spec, CommitContext ctx);

    PropertyTypeDef defineProperty(String typeApiName, NewPropertyType spec, CommitContext ctx);

    LinkTypeDef defineLinkType(NewLinkType spec, CommitContext ctx);

    /** Bir alanı kullanımdan kaldırır. Veri durur, yalnızca işaretlenir. */
    void deprecateProperty(String typeApiName, String propertyApiName, CommitContext ctx);

    List<ObjectTypeDef> objectTypes();

    Optional<ObjectTypeDef> objectType(String apiName);

    List<LinkTypeDef> linkTypes();

    /**
     * Şema önbelleğini veritabanından yeniden yükler.
     *
     * <p>Şemayı bu örnek üzerinden değiştirmek önbelleği zaten tazeler. Bu metot, şemanın
     * dışarıdan değiştiği durumlar içindir: başka bir uygulama örneği, elle çalıştırılan
     * bir migration, ya da testlerin veritabanını sıfırlaması.
     */
    void refreshSchema();

    // ------------------------------------------------------------- yazma

    /**
     * Birden çok yazma çağrısını tek atomik değişiklik olarak kaydetmek için bir commit açar.
     * Dönen bağlam ile yapılan tüm yazmalar aynı {@code commit_id}'yi taşır.
     */
    CommitContext openCommit(CommitContext ctx);

    ObjectRef createObject(String typeApiName, String externalId, CommitContext ctx);

    ObjectRef createObject(String typeApiName, String externalId,
                           Map<String, Value> values, Instant validFrom, CommitContext ctx);

    /** Var olanı döner, yoksa oluşturur. Ingest hatları için. */
    ObjectRef findOrCreate(String typeApiName, String externalId, CommitContext ctx);

    /**
     * Alanı {@code validFrom} anından itibaren bu değere ayarlar. Aynı alanın o anda
     * açık olan değeri {@code validFrom}'da kapatılır — silinmez.
     */
    void setProperty(ObjectRef obj, String property, Value value, Instant validFrom, CommitContext ctx);

    /**
     * Alanı yalnızca {@code [validFrom, validTo)} aralığı için ayarlar.
     *
     * <p>Geçmişteki bir boşluğu doldurmak içindir; mevcut bir aralığı <em>kesmez</em>.
     * Çakışma varsa {@link OntologyException.TemporalConflict} fırlatır — sessizce veri
     * kaybetmek yerine hata verir.
     */
    void setProperty(ObjectRef obj, String property, Value value,
                     Instant validFrom, Instant validTo, CommitContext ctx);

    /** Birden çok alanı tek commit altında ayarlar. */
    void setProperties(ObjectRef obj, Map<String, Value> values, Instant validFrom, CommitContext ctx);

    /** LIST kardinaliteli alanın tüm elemanlarını değiştirir. */
    void setPropertyList(ObjectRef obj, String property, List<Value> values,
                         Instant validFrom, CommitContext ctx);

    /** Alanın geçerliliğini bitirir: "bu değer artık doğru değil, yerine yenisi yok". */
    void closeProperty(ObjectRef obj, String property, Instant validTo, CommitContext ctx);

    /**
     * Açık kaydı geri çeker: "bu kaydı hiç yapmamalıydık, yanlıştı".
     *
     * <p>{@link #closeProperty}'den farkı: kapatma "artık doğru değil", geri çekme
     * "hiç doğru değildi" demektir. Satır silinmez, damgalanır.
     */
    void retractProperty(ObjectRef obj, String property, CommitContext ctx);

    void deleteObject(ObjectRef obj, CommitContext ctx);

    void link(ObjectRef from, String linkApiName, ObjectRef to,
              LinkProperties props, Instant validFrom, CommitContext ctx);

    void unlink(ObjectRef from, String linkApiName, ObjectRef to, Instant validTo, CommitContext ctx);

    // ------------------------------------------------------------- okuma

    Optional<ObjectRef> findByExternalId(String typeApiName, String externalId);

    /** Bugünkü en iyi bilgimiz. {@code object_current} projeksiyonundan okur. */
    Optional<ObjectView> current(ObjectRef obj);

    /**
     * {@code knowledgeTime} anında <em>bildiğimiz</em> hâl.
     *
     * <p>Sonradan öğrenilen ve sonradan geri çekilen kayıtlar dışarıda kalır.
     * Backtest'in ve karar denetiminin dürüstlüğü bu metoda dayanır.
     */
    Optional<ObjectView> asOf(ObjectRef obj, Instant knowledgeTime);

    /** Bir alanın tüm geçmişi — geri çekilmiş kayıtlar dahil. */
    List<PropertyHistoryEntry> history(ObjectRef obj, String property);

    /** Nesnenin giden ilişkileri. {@code knowledgeTime} null ise güncel. */
    List<LinkView> links(ObjectRef obj, Instant knowledgeTime);

    QueryResult query(OntologyQuery query);

    /**
     * Bilgi zamanına sabitlenmiş, değişmez görünüm.
     *
     * <p>Analiz turu ve backtest bunu kullanır: tur boyunca dünya değişmez, aynı girdi
     * aynı kararı üretir. Canlı sistem ile backtest arasındaki tek fark, buraya verilen
     * {@code knowledgeTime}'dır.
     */
    OntologySnapshot snapshot(Instant knowledgeTime);
}
