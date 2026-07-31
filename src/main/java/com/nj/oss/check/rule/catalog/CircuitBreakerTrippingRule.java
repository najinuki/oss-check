package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.rule.DiagnosticRule;
import com.nj.oss.check.rule.Evidence;
import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.IndexEntry;
import com.nj.oss.check.snapshot.NodesStats;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OSC-001 — the parent circuit breaker has tripped on a node that is still
 * under heap pressure.
 *
 * <p>Neither half is worth reporting alone. {@code tripped} counts up from node
 * start and never resets, so on its own it may be describing an incident from
 * months ago that has long since passed. Current heap occupancy on its own is
 * normal operation — the JVM is supposed to use its heap. Together they say the
 * node is rejecting work now.
 *
 * <p>This rule is never skipped: both halves come from {@code _nodes/stats},
 * which is REQUIRED. {@code cat_indices} only enriches it (DESIGN.md 5).
 */
public final class CircuitBreakerTrippingRule implements DiagnosticRule {

    static final String PARENT_BREAKER = "parent";

    /**
     * The parent breaker trips at 95% of heap by default. Ten points below that
     * is close enough that the node is still in the state which tripped it —
     * one expensive query away from tripping again — while leaving room for a
     * node that has genuinely recovered to fall out of range.
     *
     * <p>Higher and the rule only fires on nodes already at the trip point,
     * which is too late to be a warning. Lower and it starts reporting healthy
     * clusters, since a JVM is expected to fill its heap between collections.
     */
    static final int HEAP_PRESSURE_PERCENT = 85;

    /**
     * Query Insights writes its top-N query records to local indices under this
     * prefix. Left unmanaged they grow without bound on the very cluster whose
     * heap is already under pressure.
     */
    static final String QUERY_INSIGHTS_INDEX_PREFIX = "top_queries-";

    @Override
    public String id() {
        return "OSC-001";
    }

    @Override
    public Severity severity() {
        return Severity.CRITICAL;
    }

    @Override
    public RuleResult evaluate(ClusterSnapshot snapshot) {
        List<Map.Entry<String, NodesStats.NodeStats>> straining = snapshot.nodesStats().nodes()
                .entrySet().stream()
                .filter(node -> isStraining(node.getValue()))
                .sorted(Map.Entry.comparingByKey())
                .toList();

        if (straining.isEmpty()) {
            return RuleResult.notFired();
        }

        List<Evidence> evidence = new ArrayList<>();
        for (Map.Entry<String, NodesStats.NodeStats> node : straining) {
            NodesStats.NodeStats stats = node.getValue();
            String prefix = "nodes." + stats.name();
            evidence.add(new Evidence(prefix + ".breakers.parent.tripped",
                    String.valueOf(stats.breakers().get(PARENT_BREAKER).tripped())));
            evidence.add(new Evidence(prefix + ".jvm.mem.heap_used_percent",
                    String.valueOf(stats.jvm().mem().heapUsedPercent())));
        }

        QueryInsights insights = queryInsights(snapshot);
        evidence.add(insights.evidence());

        return RuleResult.fired(new Finding(
                id(),
                severity(),
                // Deliberately not "requests are being rejected": tripped counts
                // up from node start, so the evidence supports a node sitting in
                // the state that trips it, not a rejection happening right now.
                "Parent circuit breaker has tripped on " + nodeCount(straining.size())
                        + " still holding above " + HEAP_PRESSURE_PERCENT
                        + "% heap; the pressure that tripped it has not passed",
                evidence,
                recommendation(insights)));
    }

    private static boolean isStraining(NodesStats.NodeStats node) {
        NodesStats.Breaker parent = node.breakers().get(PARENT_BREAKER);
        return parent != null
                && parent.tripped() > 0
                && node.jvm().mem().heapUsedPercent() >= HEAP_PRESSURE_PERCENT;
    }

    /**
     * "No such index" and "could not look" are different facts, so they produce
     * different evidence rather than both silently producing none.
     */
    private static QueryInsights queryInsights(ClusterSnapshot snapshot) {
        Optional<List<IndexEntry>> indices = snapshot.indices();
        if (indices.isEmpty()) {
            return new QueryInsights(new Evidence(
                    "indices." + QUERY_INSIGHTS_INDEX_PREFIX + "*",
                    "not checked (" + snapshot.absenceReason(CollectTarget.CAT_INDICES) + ")"),
                    0, 0);
        }
        List<IndexEntry> found = indices.get().stream()
                .filter(index -> index.index() != null && index.index().startsWith(QUERY_INSIGHTS_INDEX_PREFIX))
                .toList();
        long bytes = found.stream()
                .map(IndexEntry::storeSizeBytes)
                .filter(size -> size != null)
                .mapToLong(Long::longValue)
                .sum();
        return new QueryInsights(new Evidence(
                "indices." + QUERY_INSIGHTS_INDEX_PREFIX + "*",
                found.size() + " indices, " + bytes + " bytes"),
                found.size(), bytes);
    }

    private static String recommendation(QueryInsights insights) {
        String base = "Find what is filling the heap before raising any limit: "
                + "GET _nodes/stats/breaker and GET _cat/fielddata?v&s=size:desc.";
        if (insights.indexCount() == 0) {
            return base;
        }
        return base + " Query Insights is also keeping " + insights.indexCount()
                + " " + QUERY_INSIGHTS_INDEX_PREFIX + "* indices (" + insights.bytes()
                + " bytes) on this cluster; they are never expired automatically. "
                + "Delete the old ones (DELETE " + QUERY_INSIGHTS_INDEX_PREFIX
                + "*) or set search.insights.top_queries.exporter.delete_after_days.";
    }

    private static String nodeCount(int count) {
        return count == 1 ? "1 node" : count + " nodes";
    }

    private record QueryInsights(Evidence evidence, int indexCount, long bytes) {
    }
}
