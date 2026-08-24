package com.investor.ontology.internal;

import java.util.Map;

/**
 * Derlenmiş sorgu.
 *
 * <p>SQL metninde hiçbir kullanıcı değeri yoktur; tüm değerler {@code params} üzerinden
 * bağlanır. Metne giren tek dinamik parça, {@code property_type}'a karşı doğrulanmış
 * kolon ifadeleridir.
 */
record CompiledQuery(String sql, String countSql, Map<String, Object> params) {
}
