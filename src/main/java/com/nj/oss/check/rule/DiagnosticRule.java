package com.nj.oss.check.rule;

import com.nj.oss.check.snapshot.ClusterSnapshot;

/**
 * A diagnostic rule. Rules see only the immutable {@link ClusterSnapshot} and
 * never know whether it came from a live cluster or an offline dump.
 */
public interface DiagnosticRule {

    /** Rule identifier in {@code "OSC-001"} format. */
    String id();

    /** Nominal (worst-case) severity of this rule; the actual severity of an occurrence is on the {@link Finding}. */
    Severity severity();

    /**
     * Evaluates the snapshot.
     *
     * <p>When data the rule needs is absent, return
     * {@link RuleResult#skipped(String)} — typically with
     * {@code ClusterSnapshot.absenceReason(target)} — rather than
     * {@link RuleResult#notFired()}. Returning "not fired" for missing data
     * reports a problem as absent when it was merely never looked for.
     */
    RuleResult evaluate(ClusterSnapshot snapshot);
}