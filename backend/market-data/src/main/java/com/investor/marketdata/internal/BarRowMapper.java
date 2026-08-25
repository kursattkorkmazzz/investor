package com.investor.marketdata.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

import com.investor.marketdata.model.Bar;
import com.investor.marketdata.model.Timeframe;

import org.springframework.jdbc.core.RowMapper;

final class BarRowMapper implements RowMapper<Bar> {

    static final BarRowMapper INSTANCE = new BarRowMapper();

    private BarRowMapper() {
    }

    @Override
    public Bar mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Bar(
                rs.getLong("instrument_id"),
                Timeframe.ofCode(rs.getString("timeframe")),
                rs.getObject("open_time", OffsetDateTime.class).toInstant(),
                rs.getObject("close_time", OffsetDateTime.class).toInstant(),
                rs.getBigDecimal("open"),
                rs.getBigDecimal("high"),
                rs.getBigDecimal("low"),
                rs.getBigDecimal("close"),
                rs.getBigDecimal("volume"),
                rs.getBigDecimal("quote_volume"),
                rs.getInt("trade_count"),
                rs.getBigDecimal("taker_buy_base"),
                rs.getBoolean("is_final"));
    }
}
