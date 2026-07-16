package com.nj.oss.check.rule;

import java.util.List;
import java.util.Objects;

/**
 * Result of a rule that fired. Structure is uniform across all rules.
 *
 * @param ruleId         rule identifier, e.g. {@code "OSC-001"}
 * @param severity       actual severity of this occurrence (a rule may fire at
 *                       different severities, e.g. OSC-002 CRITICAL vs WARNING)
 * @param finding        one-line statement of what is wrong
 * @param evidence       API response fields and values this conclusion is based on
 * @param recommendation actionable remediation, including concrete API call examples
 */
public record Finding(
        String ruleId,
        Severity severity,
        String finding,
        List<Evidence> evidence,
        String recommendation) {

    public Finding {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(recommendation, "recommendation");
        evidence = List.copyOf(evidence);
    }
}