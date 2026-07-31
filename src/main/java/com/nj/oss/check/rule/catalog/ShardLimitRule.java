package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.rule.DiagnosticRule;
import com.nj.oss.check.rule.Evidence;
import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.snapshot.ClusterSettings;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.ShardEntry;

import java.util.List;
import java.util.Optional;

/**
 * OSC-002 — the cluster is at or near the shard count it is allowed to hold.
 *
 * <p>The ceiling is {@code cluster.max_shards_per_node} multiplied by the
 * number of data nodes. Reaching it does not degrade anything already running:
 * it stops the <em>next</em> index from being created, which is why it usually
 * surfaces as a failing write at an inconvenient hour rather than as a red
 * cluster.
 */
public final class ShardLimitRule implements DiagnosticRule {

    static final String MAX_SHARDS_PER_NODE = "cluster.max_shards_per_node";

    /**
     * Shard counts climb through routine indexing (a daily index adds its
     * shards every night), so the last 10% is the last few days of warning
     * anyone gets. Below that there is nothing to act on yet.
     */
    static final double WARNING_RATIO = 0.9;

    @Override
    public String id() {
        return "OSC-002";
    }

    @Override
    public Severity severity() {
        return Severity.CRITICAL;
    }

    @Override
    public RuleResult evaluate(ClusterSnapshot snapshot) {
        Optional<ClusterSettings> settings = snapshot.settings();
        if (settings.isEmpty()) {
            return RuleResult.skipped(snapshot.absenceReason(CollectTarget.CLUSTER_SETTINGS));
        }
        Optional<List<ShardEntry>> shards = snapshot.shards();
        if (shards.isEmpty()) {
            return RuleResult.skipped(snapshot.absenceReason(CollectTarget.CAT_SHARDS));
        }

        String configured = settings.get().effective(MAX_SHARDS_PER_NODE).orElse(null);
        if (configured == null) {
            return RuleResult.skipped("requires " + MAX_SHARDS_PER_NODE
                    + " (absent from cluster_settings.json)");
        }
        long perNode;
        try {
            perNode = Long.parseLong(configured.trim());
        } catch (NumberFormatException e) {
            // Reporting nothing here would be indistinguishable from "the
            // cluster is fine", which it may well not be.
            return RuleResult.skipped(MAX_SHARDS_PER_NODE + " is not a number: \"" + configured + "\"");
        }

        long dataNodes = snapshot.nodesStats().dataNodeCount();
        long limit = perNode * dataNodes;
        if (limit <= 0) {
            return RuleResult.skipped("cannot compute a shard limit from "
                    + MAX_SHARDS_PER_NODE + "=" + perNode + " and " + dataNodes + " data nodes");
        }

        // Every shard of an open index counts against the limit, assigned or
        // not — which is exactly what _cat/shards lists.
        long used = shards.get().size();
        double ratio = (double) used / limit;
        Severity severity;
        String summary;
        if (used >= limit) {
            severity = Severity.CRITICAL;
            summary = "Cluster is at its shard limit (" + used + " of " + limit
                    + "); no new index can be created";
        } else if (ratio >= WARNING_RATIO) {
            severity = Severity.WARNING;
            summary = "Cluster is approaching its shard limit (" + used + " of " + limit
                    + ", " + percent(ratio) + "%)";
        } else {
            return RuleResult.notFired();
        }

        return RuleResult.fired(new Finding(
                id(),
                severity,
                summary,
                List.of(
                        new Evidence("cluster.settings." + MAX_SHARDS_PER_NODE, String.valueOf(perNode)),
                        new Evidence("nodes.stats.data_node_count", String.valueOf(dataNodes)),
                        new Evidence("cat.shards.count", String.valueOf(used)),
                        new Evidence("shard_limit", String.valueOf(limit))),
                "Reduce shard count or add data nodes; raising " + MAX_SHARDS_PER_NODE
                        + " buys time without addressing why there are " + used + " shards. "
                        + "Look for over-sharded indices first: "
                        + "GET _cat/indices?v&s=pri:desc and consider shrinking or "
                        + "rolling up small daily indices."));
    }

    private static long percent(double ratio) {
        return Math.round(ratio * 100);
    }
}
