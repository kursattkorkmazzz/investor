/**
 * Ontoloji çekirdeği — dinamik, bitemporal bilgi katmanı.
 *
 * <p>Diğer modüller yalnızca bu paketteki {@link com.investor.ontology.OntologyStore} ve
 * {@link com.investor.ontology.OntologySnapshot} arayüzlerine, {@code model} ve {@code query}
 * adlandırılmış arayüzlerine bağımlıdır. {@code internal} paketi Spring Modulith tarafından
 * kapalıdır; oraya erişen bir {@code import} build'i kırar.
 */
package com.investor.ontology;
