package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.testsupport.ClusterSnapshotBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTrippingRuleTest {

    private static final String NODE_1 = "aAbBcCdDeEfFgGhH0001";

    private final CircuitBreakerTrippingRule rule = new CircuitBreakerTrippingRule();

    @Test
    void firesWhenABreakerTrippedAndTheNodeIsStillUnderPressure() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .nodeUnderBreakerPressure(NODE_1, 847, 96)
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.ruleId()).isEqualTo("OSC-001");
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(finding.finding()).contains("1 node");
        assertThat(finding.evidence()).extracting(e -> e.source() + " = " + e.value())
                .contains("nodes.node-1.breakers.parent.tripped = 847",
                        "nodes.node-1.jvm.mem.heap_used_percent = 96");
    }

    @Test
    void doesNotFireOnAHealthyCluster() {
        // the guard against a rule that fires for the wrong reason
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy().build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void doesNotFireOnTripsTheNodeHasRecoveredFrom() {
        // tripped never resets, so without the heap check this would report an
        // incident from whenever the node last restarted
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy()
                .nodeUnderBreakerPressure(NODE_1, 847, CircuitBreakerTrippingRule.HEAP_PRESSURE_PERCENT - 1)
                .build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void doesNotFireOnHighHeapAlone() {
        // a JVM using its heap is doing its job
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy()
                .nodeUnderBreakerPressure(NODE_1, 0, 99)
                .build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void firesExactlyAtTheHeapThreshold() {
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy()
                .nodeUnderBreakerPressure(NODE_1, 1, CircuitBreakerTrippingRule.HEAP_PRESSURE_PERCENT)
                .build()))
                .isInstanceOf(RuleResult.Fired.class);
    }

    @Test
    void namesTheQueryInsightsIndicesWhenTheyAreThere() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .nodeUnderBreakerPressure(NODE_1, 847, 96)
                .index("top_queries-2026.07.30", 3_000_000_000L)
                .index("top_queries-2026.07.31", 2_000_000_000L)
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.evidence()).extracting(e -> e.source() + " = " + e.value())
                .contains("indices.top_queries-* = 2 indices, 5000000000 bytes");
        assertThat(finding.recommendation()).contains("DELETE top_queries-*");
    }

    @Test
    void firesWithoutCatIndicesAndSaysItCouldNotLook() {
        // breaker and heap both come from _nodes/stats, which is REQUIRED, so a
        // 403 on an unrelated endpoint must not cost us the finding (DESIGN.md 5)
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .nodeUnderBreakerPressure(NODE_1, 847, 96)
                .withoutIndices()
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.evidence()).extracting(e -> e.source() + " = " + e.value())
                .anyMatch(rendered -> rendered.startsWith("indices.top_queries-* = not checked")
                        && rendered.contains("cat_indices.json"));
        assertThat(finding.recommendation()).doesNotContain("DELETE top_queries-*");
    }
}
