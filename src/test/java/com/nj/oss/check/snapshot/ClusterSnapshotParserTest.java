package com.nj.oss.check.snapshot;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.testsupport.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterSnapshotParserTest {

    private final ClusterSnapshotParser parser = new ClusterSnapshotParser();

    @Test
    void parsesNormalClusterFixture() {
        ClusterSnapshot snapshot = parser.parse(Fixtures.load("normal"));

        assertThat(snapshot.metadata().collectedAt()).isEqualTo(Instant.parse("2026-07-16T08:00:00Z"));
        assertThat(snapshot.metadata().toolVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(snapshot.metadata().clusterName()).isEqualTo("fixture-cluster");
        assertThat(snapshot.metadata().clusterVersion()).isEqualTo("2.19.1");

        ClusterHealth health = snapshot.health();
        assertThat(health.clusterName()).isEqualTo("fixture-cluster");
        assertThat(health.status()).isEqualTo(HealthStatus.GREEN);
        assertThat(health.numberOfNodes()).isEqualTo(3);
        assertThat(health.numberOfDataNodes()).isEqualTo(3);
        assertThat(health.activeShards()).isEqualTo(10);
        assertThat(health.unassignedShards()).isZero();
        assertThat(health.activeShardsPercentAsNumber()).isEqualTo(100.0);
    }

    @Test
    void flattensSettingsAndAppliesPrecedence() {
        ClusterSnapshot snapshot = parser.parse(Fixtures.load("normal"));
        ClusterSettings settings = snapshot.settings();

        // persistent overrides defaults; both hold the same value here but the
        // key must be visible as explicitly set
        assertThat(settings.explicit("cluster.max_shards_per_node")).contains("1000");
        assertThat(settings.effective("cluster.max_shards_per_node")).contains("1000");

        // defaults-only key: effective sees it, explicit does not
        assertThat(settings.effective("cluster.routing.allocation.enable")).contains("all");
        assertThat(settings.explicit("cluster.routing.allocation.enable")).isEmpty();

        // arrays flatten to a comma-joined string
        assertThat(settings.effective("discovery.seed_hosts")).contains("10.0.0.1,10.0.0.2,10.0.0.3");

        assertThat(settings.effective("no.such.setting")).isEmpty();
    }

    @Test
    void treatsExplainErrorBodyAsAbsent() {
        // a healthy cluster has no unassigned shard, so the explain API
        // returns an error body — that must map to empty, not a parse failure
        ClusterSnapshot snapshot = parser.parse(Fixtures.load("normal"));
        assertThat(snapshot.allocationExplain()).isEmpty();
    }

    @Test
    void parsesUnassignedShardExplain() {
        RawDump dump = withPayload(Fixtures.load("normal"), CollectTarget.ALLOCATION_EXPLAIN, """
                {
                  "index": "logs-2026.07.15",
                  "shard": 1,
                  "primary": true,
                  "current_state": "unassigned",
                  "unassigned_info": {
                    "reason": "NODE_LEFT",
                    "at": "2026-07-16T07:55:00.000Z",
                    "details": "node_left [aAbBcCdDeEfFgGhH0002]"
                  },
                  "can_allocate": "no",
                  "allocate_explanation": "cannot allocate because allocation is not permitted to any of the nodes"
                }
                """);

        AllocationExplain explain = parser.parse(dump).allocationExplain().orElseThrow();
        assertThat(explain.index()).isEqualTo("logs-2026.07.15");
        assertThat(explain.shard()).isEqualTo(1);
        assertThat(explain.primary()).isTrue();
        assertThat(explain.currentState()).isEqualTo("unassigned");
        assertThat(explain.unassignedInfo().reason()).isEqualTo("NODE_LEFT");
        assertThat(explain.canAllocate()).isEqualTo("no");
    }

    @Test
    void parsesNodesStats() {
        NodesStats stats = parser.parse(Fixtures.load("normal")).nodesStats();

        assertThat(stats.clusterName()).isEqualTo("fixture-cluster");
        assertThat(stats.nodes()).hasSize(3);
        assertThat(stats.dataNodeCount()).isEqualTo(3);

        NodesStats.NodeStats node1 = stats.nodes().get("aAbBcCdDeEfFgGhH0001");
        assertThat(node1.name()).isEqualTo("node-1");
        assertThat(node1.isDataNode()).isTrue();
        assertThat(node1.jvm().mem().heapUsedPercent()).isEqualTo(30);
        assertThat(node1.jvm().mem().heapMaxInBytes()).isEqualTo(4294967296L);
        assertThat(node1.breakers().get("parent").tripped()).isZero();
        assertThat(node1.breakers().get("parent").limitSizeInBytes()).isEqualTo(4080218931L);
    }

    @Test
    void parsesCatResponses() {
        ClusterSnapshot snapshot = parser.parse(Fixtures.load("normal"));

        assertThat(snapshot.shards()).hasSize(10);
        ShardEntry firstShard = snapshot.shards().getFirst();
        assertThat(firstShard.index()).isEqualTo("logs-2026.07.15");
        assertThat(firstShard.shard()).isZero();
        assertThat(firstShard.isPrimary()).isTrue();
        assertThat(firstShard.isUnassigned()).isFalse();
        assertThat(firstShard.docs()).isEqualTo(125000L);
        assertThat(firstShard.storeBytes()).isEqualTo(52428800L);
        assertThat(firstShard.node()).isEqualTo("node-1");

        assertThat(snapshot.indices()).hasSize(4);
        IndexEntry logs = snapshot.indices().getFirst();
        assertThat(logs.index()).isEqualTo("logs-2026.07.15");
        assertThat(logs.pri()).isEqualTo(2);
        assertThat(logs.rep()).isEqualTo(1);
        assertThat(logs.docsCount()).isEqualTo(249300L);
        assertThat(logs.storeSizeBytes()).isEqualTo(207618048L);

        assertThat(snapshot.allocations()).hasSize(3);
        NodeAllocation node1 = snapshot.allocations().getFirst();
        assertThat(node1.node()).isEqualTo("node-1");
        assertThat(node1.shards()).isEqualTo(4);
        assertThat(node1.diskPercent()).isEqualTo(12);
        assertThat(node1.isUnassignedRow()).isFalse();
    }

    @Test
    void failsLoudlyWhenRequiredFileMissing() {
        RawDump normal = Fixtures.load("normal");
        Map<CollectTarget, String> withoutHealth = new HashMap<>(normal.payloads());
        withoutHealth.remove(CollectTarget.CLUSTER_HEALTH);
        RawDump dump = new RawDump(normal.metadataJson(), withoutHealth);

        assertThatThrownBy(() -> parser.parse(dump))
                .isInstanceOf(SnapshotParseException.class)
                .hasMessageContaining("cluster_health.json");
    }

    private static RawDump withPayload(RawDump dump, CollectTarget target, String json) {
        Map<CollectTarget, String> payloads = new HashMap<>(dump.payloads());
        payloads.put(target, json);
        return new RawDump(dump.metadataJson(), payloads);
    }
}