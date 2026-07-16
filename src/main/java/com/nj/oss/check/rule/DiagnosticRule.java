package com.nj.oss.check.rule;

import com.nj.oss.check.snapshot.ClusterSnapshot;

import java.util.Optional;

/**
 * A diagnostic rule. Rules see only the immutable {@link ClusterSnapshot} and
 * never know whether it came from a live cluster or an offline dump.
 */
public interface DiagnosticRule {

    /** Rule identifier in {@code "OSC-001"} format. */
    String id();

    /** Nominal (worst-case) severity of this rule; the actual severity of an occurrence is on the {@link Finding}. */
    Severity severity();

    /** Evaluates the snapshot; empty when the rule does not fire. */
    Optional<Finding> evaluate(ClusterSnapshot snapshot);
}