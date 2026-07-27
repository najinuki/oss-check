package com.nj.oss.check.snapshot.parse;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.snapshot.AllocationExplain;
import com.nj.oss.check.snapshot.ClusterHealth;
import com.nj.oss.check.snapshot.ClusterSettings;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.IndexEntry;
import com.nj.oss.check.snapshot.NodeAllocation;
import com.nj.oss.check.snapshot.NodesStats;
import com.nj.oss.check.snapshot.ShardEntry;
import com.nj.oss.check.snapshot.SnapshotMetadata;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.function.Function;

/**
 * Turns a {@link RawDump} into a fully typed {@link ClusterSnapshot}.
 * The parser is the single place that knows the wire format of each API.
 */
public final class ClusterSnapshotParser {

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            // Forward compatibility: a dump from a newer tool may name collect
            // targets or statuses this version has no constant for. Those
            // become null and are dropped, rather than failing the whole read.
            .enable(EnumFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
            .build();

    public ClusterSnapshot parse(RawDump dump) {
        SnapshotMetadata metadata = readValue(CollectTarget.METADATA_FILE_NAME, dump.metadataJson(), SnapshotMetadata.class);
        ClusterHealth health = readValue(required(dump, CollectTarget.CLUSTER_HEALTH), CollectTarget.CLUSTER_HEALTH, ClusterHealth.class);
        NodesStats nodesStats = parseNodesStats(required(dump, CollectTarget.NODES_STATS));
        return new ClusterSnapshot(
                metadata,
                health,
                nodesStats,
                optional(dump, CollectTarget.CLUSTER_SETTINGS, this::parseSettings),
                parseAllocationExplain(dump.payload(CollectTarget.ALLOCATION_EXPLAIN)),
                optional(dump, CollectTarget.CAT_SHARDS, this::parseCatShards),
                optional(dump, CollectTarget.CAT_INDICES, this::parseCatIndices),
                optional(dump, CollectTarget.CAT_ALLOCATION, this::parseCatAllocation));
    }

    /**
     * A REQUIRED target is one without which no rule could run, so its absence
     * is an execution error rather than a diagnosis with gaps.
     */
    private static String required(RawDump dump, CollectTarget target) {
        return dump.payload(target).orElseThrow(
                () -> new SnapshotParseException("Dump is missing required file: " + target.fileName()));
    }

    /**
     * An OPTIONAL target that is absent yields an empty field. It is never
     * replaced with an empty value: rules must be able to tell "absent" from
     * "empty" so they report themselves skipped instead of not firing.
     *
     * <p>A payload that is present but malformed still fails loudly — that is a
     * broken dump, not a partial one.
     */
    private static <T> Optional<T> optional(RawDump dump, CollectTarget target, Function<String, T> parse) {
        return dump.payload(target)
                .filter(payload -> !payload.isBlank())
                .map(parse);
    }

    private <T> T readValue(String json, CollectTarget target, Class<T> type) {
        return readValue(target.fileName(), json, type);
    }

    private <T> T readValue(String sourceName, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new SnapshotParseException("Failed to parse " + sourceName + ": " + e.getMessage(), e);
        }
    }

    private JsonNode readTree(String json, CollectTarget target) {
        try {
            return mapper.readTree(json);
        } catch (JacksonException e) {
            throw new SnapshotParseException("Failed to parse " + target.fileName() + ": " + e.getMessage(), e);
        }
    }

    private ClusterSettings parseSettings(String json) {
        JsonNode root = readTree(json, CollectTarget.CLUSTER_SETTINGS);
        return new ClusterSettings(
                flatten(root.path("persistent")),
                flatten(root.path("transient")),
                flatten(root.path("defaults")));
    }

    /** Flattens the nested settings tree into dotted keys, mirroring {@code flat_settings=true}. */
    private static Map<String, String> flatten(JsonNode node) {
        Map<String, String> flat = new LinkedHashMap<>();
        flattenInto(flat, "", node);
        return flat;
    }

    private static void flattenInto(Map<String, String> flat, String prefix, JsonNode node) {
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> property : node.properties()) {
                String key = prefix.isEmpty() ? property.getKey() : prefix + "." + property.getKey();
                flattenInto(flat, key, property.getValue());
            }
        } else if (node.isArray()) {
            StringJoiner joined = new StringJoiner(",");
            node.forEach(element -> joined.add(scalarText(element)));
            flat.put(prefix, joined.toString());
        } else if (!node.isNull() && !node.isMissingNode()) {
            flat.put(prefix, scalarText(node));
        }
    }

    private static String scalarText(JsonNode node) {
        return node.isString() ? node.stringValue() : node.toString();
    }

    /**
     * The explain API returns HTTP 400 with an error body when no shard is
     * unassigned, so an error payload (or a missing file) means "nothing to
     * explain", not a parse failure.
     */
    private Optional<AllocationExplain> parseAllocationExplain(Optional<String> payload) {
        if (payload.isEmpty() || payload.get().isBlank()) {
            return Optional.empty();
        }
        JsonNode root = readTree(payload.get(), CollectTarget.ALLOCATION_EXPLAIN);
        if (root.has("error")) {
            return Optional.empty();
        }
        return Optional.of(readValue(payload.get(), CollectTarget.ALLOCATION_EXPLAIN, AllocationExplain.class));
    }

    private NodesStats parseNodesStats(String json) {
        return readValue(json, CollectTarget.NODES_STATS, NodesStats.class);
    }

    private List<ShardEntry> parseCatShards(String json) {
        List<ShardEntry> shards = new ArrayList<>();
        for (JsonNode row : readTree(json, CollectTarget.CAT_SHARDS)) {
            shards.add(new ShardEntry(
                    text(row, "index"),
                    Integer.parseInt(text(row, "shard")),
                    text(row, "prirep"),
                    text(row, "state"),
                    longOrNull(row, "docs"),
                    SizeParser.parseBytes(text(row, "store")),
                    text(row, "node")));
        }
        return shards;
    }

    private List<IndexEntry> parseCatIndices(String json) {
        List<IndexEntry> indices = new ArrayList<>();
        for (JsonNode row : readTree(json, CollectTarget.CAT_INDICES)) {
            indices.add(new IndexEntry(
                    text(row, "health"),
                    text(row, "status"),
                    text(row, "index"),
                    intOrNull(row, "pri"),
                    intOrNull(row, "rep"),
                    longOrNull(row, "docs.count"),
                    SizeParser.parseBytes(text(row, "store.size"))));
        }
        return indices;
    }

    private List<NodeAllocation> parseCatAllocation(String json) {
        List<NodeAllocation> allocations = new ArrayList<>();
        for (JsonNode row : readTree(json, CollectTarget.CAT_ALLOCATION)) {
            allocations.add(new NodeAllocation(
                    intOrNull(row, "shards"),
                    SizeParser.parseBytes(text(row, "disk.indices")),
                    SizeParser.parseBytes(text(row, "disk.used")),
                    SizeParser.parseBytes(text(row, "disk.avail")),
                    intOrNull(row, "disk.percent"),
                    text(row, "node")));
        }
        return allocations;
    }

    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (value.isNull() || value.isMissingNode()) {
            return null;
        }
        return value.isString() ? value.stringValue() : value.toString();
    }

    private static Long longOrNull(JsonNode row, String field) {
        String value = text(row, field);
        return value == null ? null : Long.parseLong(value);
    }

    private static Integer intOrNull(JsonNode row, String field) {
        String value = text(row, field);
        return value == null ? null : Integer.parseInt(value);
    }
}