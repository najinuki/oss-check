package com.nj.oss.check.snapshot.parse;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.CollectionOutcome;
import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.snapshot.AllocationExplain;
import com.nj.oss.check.snapshot.ClusterHealth;
import com.nj.oss.check.snapshot.ClusterSettings;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.HealthStatus;
import com.nj.oss.check.snapshot.IndexEntry;
import com.nj.oss.check.snapshot.NodeAllocation;
import com.nj.oss.check.snapshot.NodesStats;
import com.nj.oss.check.snapshot.ShardEntry;
import com.nj.oss.check.snapshot.SnapshotMetadata;
import com.nj.oss.check.testsupport.Fixtures;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
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
        ClusterSettings settings = snapshot.settings().orElseThrow();

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

        List<ShardEntry> shards = snapshot.shards().orElseThrow();
        assertThat(shards).hasSize(10);
        ShardEntry firstShard = shards.getFirst();
        assertThat(firstShard.index()).isEqualTo("logs-2026.07.15");
        assertThat(firstShard.shard()).isZero();
        assertThat(firstShard.isPrimary()).isTrue();
        assertThat(firstShard.isUnassigned()).isFalse();
        assertThat(firstShard.docs()).isEqualTo(125000L);
        assertThat(firstShard.storeBytes()).isEqualTo(52428800L);
        assertThat(firstShard.node()).isEqualTo("node-1");

        List<IndexEntry> indices = snapshot.indices().orElseThrow();
        assertThat(indices).hasSize(4);
        IndexEntry logs = indices.getFirst();
        assertThat(logs.index()).isEqualTo("logs-2026.07.15");
        assertThat(logs.pri()).isEqualTo(2);
        assertThat(logs.rep()).isEqualTo(1);
        assertThat(logs.docsCount()).isEqualTo(249300L);
        assertThat(logs.storeSizeBytes()).isEqualTo(207618048L);

        List<NodeAllocation> allocations = snapshot.allocations().orElseThrow();
        assertThat(allocations).hasSize(3);
        NodeAllocation node1 = allocations.getFirst();
        assertThat(node1.node()).isEqualTo("node-1");
        assertThat(node1.shards()).isEqualTo(4);
        assertThat(node1.diskPercent()).isEqualTo(12);
        assertThat(node1.isUnassignedRow()).isFalse();
    }

    @Test
    void parsesCollectionReport() {
        SnapshotMetadata metadata = parser.parse(Fixtures.load("normal")).metadata();

        assertThat(metadata.dumpSchemaVersion()).isEqualTo(SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION);
        assertThat(metadata.isNewerThanSupported()).isFalse();
        assertThat(metadata.collection()).hasSize(CollectTarget.values().length);

        assertThat(metadata.outcomeOf(CollectTarget.CLUSTER_HEALTH).orElseThrow().isOk()).isTrue();

        CollectionOutcome explain = metadata.outcomeOf(CollectTarget.ALLOCATION_EXPLAIN).orElseThrow();
        assertThat(explain.isOk()).isFalse();
        assertThat(explain.httpStatus()).isEqualTo(400);
        assertThat(explain.describeFailure()).contains("HTTP 400: unable to find any unassigned shards to explain");
    }

    @Nested
    class RequiredTargets {

        @ParameterizedTest
        @EnumSource(value = CollectTarget.class, names = {"CLUSTER_HEALTH", "NODES_STATS"})
        void failLoudlyWhenMissing(CollectTarget required) {
            RawDump dump = withoutPayload(Fixtures.load("normal"), required);

            assertThatThrownBy(() -> parser.parse(dump))
                    .isInstanceOf(SnapshotParseException.class)
                    .hasMessageContaining(required.fileName());
        }

        @Test
        void areExactlyTheTargetsNoRuleCouldRunWithout() {
            // Guards the growth rule: a new target must be OPTIONAL, because
            // promoting one to REQUIRED makes existing dumps unreadable.
            assertThat(Arrays.stream(CollectTarget.values()).filter(CollectTarget::isRequired))
                    .containsExactly(CollectTarget.CLUSTER_HEALTH, CollectTarget.NODES_STATS);
        }
    }

    @Nested
    class OptionalTargets {

        @Test
        void parseIntoEmptyFieldsWhenAbsent() {
            ClusterSnapshot snapshot = parser.parse(Fixtures.load("required-only"));

            // never substituted with empty collections: rules must be able to
            // tell "absent" from "empty" so they can report themselves skipped
            assertThat(snapshot.settings()).isEmpty();
            assertThat(snapshot.shards()).isEmpty();
            assertThat(snapshot.indices()).isEmpty();
            assertThat(snapshot.allocations()).isEmpty();
            assertThat(snapshot.allocationExplain()).isEmpty();

            // the required data is still fully usable
            assertThat(snapshot.health().status()).isEqualTo(HealthStatus.GREEN);
            assertThat(snapshot.nodesStats().dataNodeCount()).isEqualTo(3);
        }

        @Test
        void absenceReasonQuotesTheRecordedCollectionFailure() {
            ClusterSnapshot snapshot = parser.parse(Fixtures.load("required-only"));

            assertThat(snapshot.absenceReason(CollectTarget.CLUSTER_SETTINGS))
                    .isEqualTo("requires cluster_settings.json "
                            + "(collection failed: HTTP 403: no permissions for [cluster:monitor/settings])");
        }

        @Test
        void absenceReasonFallsBackWhenDumpRecordsNoOutcome() {
            // what an older dump looks like: taken before the target existed,
            // so its collection report says nothing about it
            RawDump dump = withoutPayload(Fixtures.load("normal"), CollectTarget.CAT_INDICES);
            ClusterSnapshot snapshot = parser.parse(new RawDump("""
                    { "collected_at": "2026-07-16T08:00:00Z", "tool_version": "0.0.9" }
                    """, dump.payloads()));

            assertThat(snapshot.absenceReason(CollectTarget.CAT_INDICES))
                    .isEqualTo("requires cat_indices.json (not in dump)");
        }

        @Test
        void stillFailLoudlyWhenPresentButMalformed() {
            // a broken payload is a broken dump, not a partial one
            RawDump dump = withPayload(Fixtures.load("normal"), CollectTarget.CAT_SHARDS, "{ not json");

            assertThatThrownBy(() -> parser.parse(dump))
                    .isInstanceOf(SnapshotParseException.class)
                    .hasMessageContaining("cat_shards.json");
        }
    }

    @Nested
    class ForwardCompatibility {

        @Test
        void readsDumpsFromNewerToolsBestEffort() {
            RawDump normal = Fixtures.load("normal");
            RawDump fromNewerTool = new RawDump("""
                    {
                      "dump_schema_version": 99,
                      "collected_at": "2026-07-16T08:00:00Z",
                      "tool_version": "9.9.9",
                      "cluster_name": "fixture-cluster",
                      "cluster_version": "3.0.0",
                      "some_field_we_do_not_know": true,
                      "collection": [
                        { "target": "CLUSTER_HEALTH", "status": "OK", "http_status": 200 },
                        { "target": "HOT_THREADS", "status": "OK", "http_status": 200 },
                        { "target": "NODES_STATS", "status": "PARTIAL", "http_status": 206 }
                      ]
                    }
                    """, normal.payloads());

            SnapshotMetadata metadata = parser.parse(fromNewerTool).metadata();

            assertThat(metadata.isNewerThanSupported()).isTrue();
            // the unknown target is dropped, the known ones survive
            assertThat(metadata.collection()).hasSize(2);
            assertThat(metadata.outcomeOf(CollectTarget.CLUSTER_HEALTH)).isPresent();
            // an unknown status maps to UNKNOWN rather than failing the read
            assertThat(metadata.outcomeOf(CollectTarget.NODES_STATS).orElseThrow().status())
                    .isEqualTo(CollectionOutcome.Status.UNKNOWN);
        }

        @Test
        void assumesSchemaVersionOneWhenFieldIsAbsent() {
            RawDump normal = Fixtures.load("normal");
            RawDump preSchemaVersion = new RawDump("""
                    { "collected_at": "2026-07-16T08:00:00Z", "tool_version": "0.0.9" }
                    """, normal.payloads());

            SnapshotMetadata metadata = parser.parse(preSchemaVersion).metadata();
            assertThat(metadata.dumpSchemaVersion()).isEqualTo(1);
            assertThat(metadata.isNewerThanSupported()).isFalse();
            assertThat(metadata.collection()).isEmpty();
        }
    }

    private static RawDump withPayload(RawDump dump, CollectTarget target, String json) {
        Map<CollectTarget, String> payloads = new HashMap<>(dump.payloads());
        payloads.put(target, json);
        return new RawDump(dump.metadataJson(), payloads);
    }

    private static RawDump withoutPayload(RawDump dump, CollectTarget target) {
        Map<CollectTarget, String> payloads = new HashMap<>(dump.payloads());
        payloads.remove(target);
        return new RawDump(dump.metadataJson(), payloads);
    }
}