package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.testsupport.ClusterSnapshotBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShardLimitRuleTest {

    /** The fixture has three data nodes, so this puts the ceiling at 30 shards. */
    private static final String TEN_PER_NODE = "10";
    private static final int LIMIT = 30;

    private final ShardLimitRule rule = new ShardLimitRule();

    @Test
    void firesCriticalWhenTheLimitIsReached() {
        RuleResult result = rule.evaluate(atLimit(LIMIT));

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.ruleId()).isEqualTo("OSC-002");
        assertThat(finding.severity()).isEqualTo(Severity.CRITICAL);
        assertThat(finding.finding()).contains("30 of 30", "no new index");
        assertThat(finding.evidence()).extracting(e -> e.source() + " = " + e.value())
                .contains("cluster.settings.cluster.max_shards_per_node = 10",
                        "nodes.stats.data_node_count = 3",
                        "cat.shards.count = 30",
                        "shard_limit = 30");
    }

    @Test
    void firesWarningWhenApproaching() {
        RuleResult result = rule.evaluate(atLimit(27));

        Finding finding = ((RuleResult.Fired) result).finding();
        assertThat(finding.severity()).isEqualTo(Severity.WARNING);
        assertThat(finding.finding()).contains("27 of 30", "90%");
    }

    @Test
    void firesExactlyAtTheWarningRatioAndNotBelowIt() {
        int atRatio = (int) (LIMIT * ShardLimitRule.WARNING_RATIO);

        assertThat(rule.evaluate(atLimit(atRatio))).isInstanceOf(RuleResult.Fired.class);
        assertThat(rule.evaluate(atLimit(atRatio - 1))).isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void doesNotFireOnAHealthyCluster() {
        // 10 shards against a 3000 ceiling
        assertThat(rule.evaluate(ClusterSnapshotBuilder.healthy().build()))
                .isInstanceOf(RuleResult.NotFired.class);
    }

    @Test
    void isSkippedWithoutSettings() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy().withoutSettings().build());

        assertThat(((RuleResult.Skipped) result).reason()).contains("cluster_settings.json");
    }

    @Test
    void isSkippedWithoutShards() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy().withoutShards().build());

        assertThat(((RuleResult.Skipped) result).reason()).contains("cat_shards.json");
    }

    @Test
    void isSkippedRatherThanSilentWhenTheSettingIsNotANumber() {
        RuleResult result = rule.evaluate(ClusterSnapshotBuilder.healthy()
                .persistentSetting(ShardLimitRule.MAX_SHARDS_PER_NODE, "lots")
                .build());

        assertThat(((RuleResult.Skipped) result).reason()).contains("not a number", "lots");
    }

    private static com.nj.oss.check.snapshot.ClusterSnapshot atLimit(int shards) {
        return ClusterSnapshotBuilder.healthy()
                .persistentSetting(ShardLimitRule.MAX_SHARDS_PER_NODE, TEN_PER_NODE)
                .shardCount(shards)
                .build();
    }
}
