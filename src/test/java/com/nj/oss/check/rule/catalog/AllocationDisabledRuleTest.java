package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.snapshot.HealthStatus;
import com.nj.oss.check.testsupport.ClusterSnapshotBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AllocationDisabledRuleTest {

    private final AllocationDisabledRule rule = new AllocationDisabledRule();

    @Test
    void firesWhenAllocationIsOffAndShardsAreWaiting() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .persistentSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "none")
                .health(HealthStatus.RED, 12)
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.ruleId()).isEqualTo("OSC-003");
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
        // the causal link is the point of this rule, not the setting on its own
        assertThat(finding.finding()).contains("12 shard(s) are unassigned", "while it stays off");
        assertThat(finding.evidence()).extracting(e -> e.source() + " = " + e.value())
                .contains("cluster.settings.persistent.cluster.routing.allocation.enable = none",
                        "cluster.health.status = RED",
                        "cluster.health.unassigned_shards = 12");
        assertThat(finding.recommendation())
                .contains("\"persistent\":{\"cluster.routing.allocation.enable\":null}")
                .doesNotContain("\"transient\"");
    }

    @Test
    void clearsTheTransientScopeWhenThatIsWhereItWasSet() {
        // the scope people actually use during a rolling restart, and the one
        // that wins — clearing persistent instead would leave allocation off
        // while the operator believes they turned it back on
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .transientSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "none")
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.evidence()).extracting(e -> e.source() + " = " + e.value())
                .contains("cluster.settings.transient.cluster.routing.allocation.enable = none");
        assertThat(finding.recommendation())
                .contains("\"transient\":{\"cluster.routing.allocation.enable\":null}")
                .doesNotContain("\"persistent\"");
    }

    @Test
    void clearsBothScopesWhenBothAreSet() {
        // removing only the transient value would let the persistent none take over
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .transientSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "none")
                .persistentSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "none")
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.recommendation())
                .contains("\"transient\":{\"cluster.routing.allocation.enable\":null}")
                .contains("\"persistent\":{\"cluster.routing.allocation.enable\":null}");
    }

    @Test
    void doesNotFireWhenATransientAllOverridesAPersistentNone() {
        // transient wins, so allocation is on; firing here would be a false
        // positive on a cluster that is behaving correctly
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy()
                .persistentSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "none")
                .transientSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "all")
                .build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void firesEvenWhileNothingIsUnassignedYet() {
        // the damage starts at the next node loss, which is exactly why this
        // gets forgotten after a rolling restart
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .persistentSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "none")
                .build());

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.finding()).contains("nothing is unassigned yet", "cannot heal");
    }

    @Test
    void doesNotFireOnAHealthyCluster() {
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy().build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void doesNotFireOnTheDefaultValueSetExplicitly() {
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy()
                .persistentSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "all")
                .build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void doesNotFireOnAPartialRestriction() {
        // "primaries" still lets primaries move; it is a different situation
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy()
                .persistentSetting(AllocationDisabledRule.ALLOCATION_ENABLE, "primaries")
                .build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void isSkippedWithoutSettings() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy().withoutSettings().build());

        assertThat(((RuleResult.Skipped) result).reason()).contains("cluster_settings.json");
    }
}
