package com.nj.oss.check.snapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, fully parsed view of everything {@code collect} gathered from a
 * cluster. This is the only input rules see; rules never know whether the data
 * came from a live cluster or an offline dump.
 *
 * <p>Field-to-endpoint mapping:
 * <ul>
 *   <li>{@code health} — {@code _cluster/health}</li>
 *   <li>{@code settings} — {@code _cluster/settings?include_defaults=true}</li>
 *   <li>{@code allocationExplain} — {@code _cluster/allocation/explain};
 *       empty when the cluster had no unassigned shard to explain (the API
 *       returns HTTP 400 in that case)</li>
 *   <li>{@code nodesStats} — {@code _nodes/stats}</li>
 *   <li>{@code shards} — {@code _cat/shards?format=json&bytes=b}</li>
 *   <li>{@code indices} — {@code _cat/indices?format=json&bytes=b}</li>
 *   <li>{@code allocations} — {@code _cat/allocation?format=json&bytes=b}</li>
 * </ul>
 */
public record ClusterSnapshot(
        SnapshotMetadata metadata,
        ClusterHealth health,
        ClusterSettings settings,
        Optional<AllocationExplain> allocationExplain,
        NodesStats nodesStats,
        List<ShardEntry> shards,
        List<IndexEntry> indices,
        List<NodeAllocation> allocations) {

    public ClusterSnapshot {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(health, "health");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(allocationExplain, "allocationExplain");
        Objects.requireNonNull(nodesStats, "nodesStats");
        shards = List.copyOf(shards);
        indices = List.copyOf(indices);
        allocations = List.copyOf(allocations);
    }
}