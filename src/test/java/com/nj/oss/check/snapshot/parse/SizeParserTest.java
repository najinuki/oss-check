package com.nj.oss.check.snapshot.parse;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SizeParserTest {

    @Test
    void parsesPlainByteCounts() {
        assertThat(SizeParser.parseBytes("0")).isZero();
        assertThat(SizeParser.parseBytes("52428800")).isEqualTo(52428800L);
    }

    @Test
    void parsesHumanReadableUnits() {
        assertThat(SizeParser.parseBytes("208b")).isEqualTo(208L);
        assertThat(SizeParser.parseBytes("1kb")).isEqualTo(1024L);
        assertThat(SizeParser.parseBytes("1.5mb")).isEqualTo(1572864L);
        assertThat(SizeParser.parseBytes("1.2gb")).isEqualTo(1288490189L);
        assertThat(SizeParser.parseBytes("2TB")).isEqualTo(2199023255552L);
    }

    @Test
    void nullAndBlankMeanAbsent() {
        assertThat(SizeParser.parseBytes(null)).isNull();
        assertThat(SizeParser.parseBytes("")).isNull();
        assertThat(SizeParser.parseBytes("  ")).isNull();
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> SizeParser.parseBytes("lots"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}