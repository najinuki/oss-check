package com.nj.oss.check.snapshot;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.CollectionOutcome;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, fully parsed view of everything {@code collect} gathered from a
 * cluster. This is the only input rules see; rules never know whether the data
 * came from a live cluster or an offline dump.
 *
 * <p>Fields backed by an OPTIONAL {@link CollectTarget} are {@link Optional}.
 * They are never substituted with an empty list or empty map: "the cluster has
 * no such data" and "we could not read it" are different facts, and collapsing
 * them turns a collection failure into a silent false negative. A rule that
 * needs absent data reports itself skipped — see {@link #absenceReason}.
 *
 * <p>Field-to-endpoint mapping:
 * <ul>
 *   <li>{@code health} — {@code _cluster/health} (required)</li>
 *   <li>{@code nodesStats} — {@code _nodes/stats} (required)</li>
 *   <li>{@code settings} — {@code _cluster/settings?include_defaults=true}</li>
 *   <li>{@code allocationExplain} — {@code _cluster/allocation/explain};
 *       empty when the cluster had no unassigned shard to explain (the API
 *       returns HTTP 400 in that case)</li>
 *   <li>{@code shards} — {@code _cat/shards?format=json&bytes=b}</li>
 *   <li>{@code indices} — {@code _cat/indices?format=json&bytes=b}</li>
 *   <li>{@code allocations} — {@code _cat/allocation?format=json&bytes=b}</li>
 * </ul>
 */
public record ClusterSnapshot(
        SnapshotMetadata metadata,
        ClusterHealth health,
        NodesStats nodesStats,
        Optional<ClusterSettings> settings,
        Optional<AllocationExplain> allocationExplain,
        Optional<List<ShardEntry>> shards,
        Optional<List<IndexEntry>> indices,
        Optional<List<NodeAllocation>> allocations) {

    public ClusterSnapshot {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(nodesStats, "nodesStats");
        settings = Objects.requireNonNull(settings, "settings");
        allocationExplain = Objects.requireNonNull(allocationExplain, "allocationExplain");
        shards = copyOf(shards, "shards");
        indices = copyOf(indices, "indices");
        allocations = copyOf(allocations, "allocations");
    }

    /**
     * Reason string for a rule to report when it cannot run because
     * {@code target}'s data is not in this snapshot, e.g.
     * {@code "requires cluster_settings.json (collection failed: HTTP 403: no permissions)"}.
     * Falls back to a plain "not in dump" when the dump records no outcome for
     * the target — which is what an older dump, taken before the target
     * existed, looks like.
     */
    public String absenceReason(CollectTarget target) {
        String detail = metadata.outcomeOf(target)
                .flatMap(CollectionOutcome::describeFailure)
                .map(failure -> "collection failed: " + failure)
                .orElse("not in dump");
        return "requires " + target.fileName() + " (" + detail + ")";
    }

    private static <T> Optional<List<T>> copyOf(Optional<List<T>> values, String name) {
        Objects.requireNonNull(values, name);
        return values.map(List::copyOf);
    }
}
