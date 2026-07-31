package com.nj.oss.check.testsupport;

import com.nj.oss.check.snapshot.ClusterHealth;
import com.nj.oss.check.snapshot.ClusterSettings;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.HealthStatus;
import com.nj.oss.check.snapshot.IndexEntry;
import com.nj.oss.check.snapshot.NodesStats;
import com.nj.oss.check.snapshot.ShardEntry;
import com.nj.oss.check.snapshot.parse.ClusterSnapshotParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Builds a snapshot by taking a real fixture and changing one thing.
 *
 * <p>Rules need three cases each (fires, does not fire, right at the boundary),
 * and writing a full dump directory for every one of them would bury the single
 * changed field in a hundred lines of realistic-looking JSON. Starting from
 * {@code fixtures/normal} also means every rule test runs against a cluster
 * that is otherwise healthy, so a rule that fires for the wrong reason has
 * nowhere to hide (DESIGN.md 6).
 */
public final class ClusterSnapshotBuilder {

    private ClusterSnapshot snapshot;

    private ClusterSnapshotBuilder(ClusterSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    /** A healthy three-node cluster with every target collected. */
    public static ClusterSnapshotBuilder healthy() {
        return from("normal");
    }

    public static ClusterSnapshotBuilder from(String fixture) {
        return new ClusterSnapshotBuilder(new ClusterSnapshotParser().parse(Fixtures.load(fixture)));
    }

    /** Gives one node a tripped parent breaker and a heap reading. */
    public ClusterSnapshotBuilder nodeUnderBreakerPressure(String nodeId, long tripped, int heapPercent) {
        NodesStats stats = snapshot.nodesStats();
        NodesStats.NodeStats node = stats.nodes().get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Fixture has no node " + nodeId + ", only " + stats.nodes().keySet());
        }
        Map<String, NodesStats.Breaker> breakers = new HashMap<>(node.breakers());
        NodesStats.Breaker parent = breakers.get("parent");
        breakers.put("parent", new NodesStats.Breaker(
                parent.limitSizeInBytes(), parent.estimatedSizeInBytes(), parent.overhead(), tripped));

        NodesStats.Jvm.Mem mem = node.jvm().mem();
        Map<String, NodesStats.NodeStats> nodes = new HashMap<>(stats.nodes());
        nodes.put(nodeId, new NodesStats.NodeStats(
                node.name(),
                node.roles(),
                new NodesStats.Jvm(new NodesStats.Jvm.Mem(
                        heapPercent, mem.heapUsedInBytes(), mem.heapMaxInBytes())),
                breakers));

        snapshot = withNodesStats(new NodesStats(stats.clusterName(), nodes));
        return this;
    }

    /** Adds an index row, e.g. a Query Insights index nobody cleaned up. */
    public ClusterSnapshotBuilder index(String name, long storeSizeBytes) {
        List<IndexEntry> indices = new ArrayList<>(snapshot.indices().orElse(List.of()));
        indices.add(new IndexEntry("green", "open", name, 1, 1, 0L, storeSizeBytes));
        snapshot = with(snapshot.settings(), Optional.of(indices), snapshot.shards());
        return this;
    }

    /** As if _cat/indices had not been collected. */
    public ClusterSnapshotBuilder withoutIndices() {
        snapshot = with(snapshot.settings(), Optional.empty(), snapshot.shards());
        return this;
    }

    /** As if _cluster/settings had not been collected. */
    public ClusterSnapshotBuilder withoutSettings() {
        snapshot = with(Optional.empty(), snapshot.indices(), snapshot.shards());
        return this;
    }

    /** As if _cat/shards had not been collected. */
    public ClusterSnapshotBuilder withoutShards() {
        snapshot = with(snapshot.settings(), snapshot.indices(), Optional.empty());
        return this;
    }

    /** Sets a value in the persistent scope, as an operator would. */
    public ClusterSnapshotBuilder persistentSetting(String key, String value) {
        ClusterSettings settings = snapshot.settings().orElseThrow();
        Map<String, String> persistent = new HashMap<>(settings.persistentSettings());
        persistent.put(key, value);
        snapshot = with(
                Optional.of(new ClusterSettings(persistent, settings.transientSettings(), settings.defaultSettings())),
                snapshot.indices(),
                snapshot.shards());
        return this;
    }

    /** Sets a value in the transient scope, as during a rolling restart. */
    public ClusterSnapshotBuilder transientSetting(String key, String value) {
        ClusterSettings settings = snapshot.settings().orElseThrow();
        Map<String, String> transientSettings = new HashMap<>(settings.transientSettings());
        transientSettings.put(key, value);
        snapshot = with(
                Optional.of(new ClusterSettings(
                        settings.persistentSettings(), transientSettings, settings.defaultSettings())),
                snapshot.indices(),
                snapshot.shards());
        return this;
    }

    /** Replaces the shard list with {@code count} identical started shards. */
    public ClusterSnapshotBuilder shardCount(int count) {
        List<ShardEntry> shards = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            shards.add(new ShardEntry("index-" + i, 0, "p", "STARTED", 0L, 0L, "node-1"));
        }
        snapshot = with(snapshot.settings(), snapshot.indices(), Optional.of(shards));
        return this;
    }

    public ClusterSnapshotBuilder health(HealthStatus status, int unassignedShards) {
        ClusterHealth health = snapshot.health();
        snapshot = new ClusterSnapshot(
                snapshot.metadata(),
                new ClusterHealth(
                        health.clusterName(), status, health.numberOfNodes(), health.numberOfDataNodes(),
                        health.activePrimaryShards(), health.activeShards(), health.relocatingShards(),
                        health.initializingShards(), unassignedShards, health.delayedUnassignedShards(),
                        health.activeShardsPercentAsNumber()),
                snapshot.nodesStats(),
                snapshot.settings(),
                snapshot.allocationExplain(),
                snapshot.shards(),
                snapshot.indices(),
                snapshot.allocations());
        return this;
    }

    public ClusterSnapshot build() {
        return snapshot;
    }

    private ClusterSnapshot withNodesStats(NodesStats nodesStats) {
        return new ClusterSnapshot(
                snapshot.metadata(), snapshot.health(), nodesStats, snapshot.settings(),
                snapshot.allocationExplain(), snapshot.shards(), snapshot.indices(), snapshot.allocations());
    }

    private ClusterSnapshot with(
            Optional<ClusterSettings> settings,
            Optional<List<IndexEntry>> indices,
            Optional<List<ShardEntry>> shards) {
        return new ClusterSnapshot(
                snapshot.metadata(), snapshot.health(), snapshot.nodesStats(), settings,
                snapshot.allocationExplain(), shards, indices, snapshot.allocations());
    }
}
