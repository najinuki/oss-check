package com.nj.oss.check.rule;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.parse.ClusterSnapshotParser;
import com.nj.oss.check.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class RuleEngineTest {

    private final ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(Fixtures.load("required-only"));

    @Test
    void sortsFindingsBySeverityThenRuleId() {
        DiagnosticReport report = new RuleEngine(List.of(
                firing("OSC-003", Severity.INFO),
                firing("OSC-002", Severity.CRITICAL),
                firing("OSC-001", Severity.WARNING),
                firing("OSC-004", Severity.CRITICAL))).run(snapshot);

        assertThat(report.findings())
                .extracting(Finding::ruleId, Finding::severity)
                .containsExactly(
                        tuple("OSC-002", Severity.CRITICAL),
                        tuple("OSC-004", Severity.CRITICAL),
                        tuple("OSC-001", Severity.WARNING),
                        tuple("OSC-003", Severity.INFO));
        assertThat(report.hasSkipped()).isFalse();
    }

    @Test
    void reportsSkippedRulesSeparatelyFromRulesThatDidNotFire() {
        DiagnosticRule needsSettings = rule("OSC-002", Severity.CRITICAL,
                s -> RuleResult.skipped(s.absenceReason(CollectTarget.CLUSTER_SETTINGS)));

        DiagnosticReport report = new RuleEngine(List.of(
                needsSettings,
                rule("OSC-001", Severity.CRITICAL, s -> RuleResult.notFired()))).run(snapshot);

        // a rule that could not run must not be indistinguishable from a clean one
        assertThat(report.hasFindings()).isFalse();
        assertThat(report.skipped()).singleElement()
                .satisfies(skipped -> {
                    assertThat(skipped.ruleId()).isEqualTo("OSC-002");
                    assertThat(skipped.reason())
                            .isEqualTo("requires cluster_settings.json "
                                    + "(collection failed: HTTP 403: no permissions for [cluster:monitor/settings])");
                });
    }

    @Test
    void sortsSkippedRulesByRuleId() {
        DiagnosticReport report = new RuleEngine(List.of(
                rule("OSC-003", Severity.CRITICAL, s -> RuleResult.skipped("no settings")),
                rule("OSC-001", Severity.CRITICAL, s -> RuleResult.skipped("no indices")))).run(snapshot);

        assertThat(report.skipped()).extracting(SkippedRule::ruleId).containsExactly("OSC-001", "OSC-003");
    }

    @Test
    void collectsFindingsAndSkipsFromTheSameRun() {
        DiagnosticReport report = new RuleEngine(List.of(
                firing("OSC-001", Severity.CRITICAL),
                rule("OSC-002", Severity.CRITICAL, s -> RuleResult.skipped("no settings")),
                rule("OSC-003", Severity.WARNING, s -> RuleResult.notFired()))).run(snapshot);

        assertThat(report.findings()).extracting(Finding::ruleId).containsExactly("OSC-001");
        assertThat(report.skipped()).extracting(SkippedRule::ruleId).containsExactly("OSC-002");
    }

    private static DiagnosticRule firing(String id, Severity severity) {
        return rule(id, severity, s -> RuleResult.fired(new Finding(
                id, severity, "something is wrong",
                List.of(new Evidence("cluster.health.status", "RED")),
                "do something about it")));
    }

    private static DiagnosticRule rule(String id, Severity severity, Evaluator evaluator) {
        return new DiagnosticRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Severity severity() {
                return severity;
            }

            @Override
            public RuleResult evaluate(ClusterSnapshot snapshot) {
                return evaluator.evaluate(snapshot);
            }
        };
    }

    @FunctionalInterface
    private interface Evaluator {
        RuleResult evaluate(ClusterSnapshot snapshot);
    }
}
