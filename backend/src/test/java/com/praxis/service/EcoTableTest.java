package com.praxis.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the ECO table loads correctly and key canonical names are present.
 * These four are the spec-mandated assertions from the refactor brief.
 */
class EcoTableTest {

    private EcoTable ecoTable;

    @BeforeEach
    void setUp() {
        ecoTable = new EcoTable();
        ecoTable.load();
    }

    @Test
    void tableLoadsSuccessfully() {
        assertThat(ecoTable.isLoaded()).isTrue();
    }

    @Test
    void c41IsPhilidorDefense() {
        assertThat(ecoTable.lookup("C41")).isEqualTo("Philidor Defense");
    }

    @Test
    void b07IsPircDefense() {
        assertThat(ecoTable.lookup("B07")).isEqualTo("Pirc Defense");
    }

    @Test
    void c68IsRuyLopezExchange() {
        assertThat(ecoTable.lookup("C68")).isEqualTo("Ruy Lopez, Exchange Variation");
    }

    @Test
    void b01IsScandinavianDefense() {
        assertThat(ecoTable.lookup("B01")).isEqualTo("Scandinavian Defense");
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(ecoTable.lookup("c41")).isEqualTo(ecoTable.lookup("C41"));
        assertThat(ecoTable.lookup("b07")).isEqualTo(ecoTable.lookup("B07"));
    }

    @Test
    void unknownCodeReturnsNull() {
        assertThat(ecoTable.lookup("Z99")).isNull();
        assertThat(ecoTable.lookup(null)).isNull();
        assertThat(ecoTable.lookup("")).isNull();
    }
}
