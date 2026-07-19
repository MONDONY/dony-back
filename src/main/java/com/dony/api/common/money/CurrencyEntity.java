package com.dony.api.common.money;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "currencies")
public class CurrencyEntity {

    // V176 declares this column CHAR(3) (bpchar in Postgres), not VARCHAR — a bare
    // String field defaults to Types#VARCHAR, which fails Hibernate's schema
    // validator (ddl-auto: validate, used by the e2e/Cucumber profile against a real
    // Postgres) with "wrong column type ... found bpchar, expecting varchar(3)".
    // @JdbcTypeCode(SqlTypes.CHAR) makes Hibernate map/validate this as CHAR to match.
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "code", length = 3, columnDefinition = "CHAR(3)")
    private String code;

    @Column(name = "numeric_code", nullable = false)
    private short numericCode;

    @Column(name = "minor_unit", nullable = false)
    private short minorUnit;

    @Column(name = "symbol", nullable = false, length = 8)
    private String symbol;

    @Column(name = "peg_rate_to_eur", precision = 18, scale = 8)
    private BigDecimal pegRateToEur;   // NULL = flottante

    @Column(name = "rounding_increment", nullable = false)
    private int roundingIncrement;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public short getNumericCode() { return numericCode; }
    public void setNumericCode(short numericCode) { this.numericCode = numericCode; }
    public short getMinorUnit() { return minorUnit; }
    public void setMinorUnit(short minorUnit) { this.minorUnit = minorUnit; }
    public String getSymbol() { return symbol; }
    public void setSymbol(String symbol) { this.symbol = symbol; }
    public BigDecimal getPegRateToEur() { return pegRateToEur; }
    public void setPegRateToEur(BigDecimal pegRateToEur) { this.pegRateToEur = pegRateToEur; }
    public int getRoundingIncrement() { return roundingIncrement; }
    public void setRoundingIncrement(int roundingIncrement) { this.roundingIncrement = roundingIncrement; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
