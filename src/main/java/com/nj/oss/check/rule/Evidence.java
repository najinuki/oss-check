package com.nj.oss.check.rule;

import java.util.Objects;

/**
 * A single piece of evidence backing a finding: which field of which API
 * response had which value.
 *
 * @param source dotted path into an API response, e.g. {@code "nodes.stats.breakers.parent.tripped"}
 * @param value  the observed value, rendered as text, e.g. {@code "847"}
 */
public record Evidence(String source, String value) {

    public Evidence {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(value, "value");
    }

    public String render() {
        return source + " = " + value;
    }
}