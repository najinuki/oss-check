package com.nj.oss.check.snapshot;

import com.fasterxml.jackson.annotation.JsonCreator;

import java.util.Locale;

public enum HealthStatus {
    GREEN,
    YELLOW,
    RED;

    @JsonCreator
    public static HealthStatus from(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}