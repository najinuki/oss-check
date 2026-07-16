package com.nj.oss.check.rule;

import com.nj.oss.check.snapshot.ClusterSnapshot;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Runs every registered rule against a snapshot and returns the findings,
 * most severe first (ties broken by rule id).
 */
public final class RuleEngine {

    private final List<DiagnosticRule> rules;

    public RuleEngine(List<DiagnosticRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public List<Finding> run(ClusterSnapshot snapshot) {
        return rules.stream()
                .map(rule -> rule.evaluate(snapshot))
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(Finding::severity).thenComparing(Finding::ruleId))
                .toList();
    }
}