package com.nj.oss.check.rule;

import com.nj.oss.check.snapshot.ClusterSnapshot;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs every registered rule against a snapshot and collects both what fired
 * and what could not be evaluated.
 */
public final class RuleEngine {

    private final List<DiagnosticRule> rules;

    public RuleEngine(List<DiagnosticRule> rules) {
        this.rules = List.copyOf(rules);
    }

    public DiagnosticReport run(ClusterSnapshot snapshot) {
        List<Finding> findings = new ArrayList<>();
        List<SkippedRule> skipped = new ArrayList<>();

        for (DiagnosticRule rule : rules) {
            switch (rule.evaluate(snapshot)) {
                case RuleResult.Fired fired -> findings.add(fired.finding());
                case RuleResult.Skipped skip -> skipped.add(new SkippedRule(rule.id(), skip.reason()));
                case RuleResult.NotFired ignored -> {
                }
            }
        }

        findings.sort(Comparator.comparing(Finding::severity).thenComparing(Finding::ruleId));
        skipped.sort(Comparator.comparing(SkippedRule::ruleId));
        return new DiagnosticReport(findings, skipped);
    }
}
