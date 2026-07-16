package com.nj.oss.check.snapshot;

/**
 * One row of {@code _cat/allocation?format=json&bytes=b}.
 *
 * <p>The API emits a synthetic row with {@code node = "UNASSIGNED"} when
 * unassigned shards exist; its disk fields are null.
 *
 * @param shards           number of shards on the node
 * @param diskIndicesBytes bytes used by shard data on the node
 * @param diskUsedBytes    total disk used on the node
 * @param diskAvailBytes   disk available on the node
 * @param diskPercent      used disk percentage (0–100)
 * @param node             node name, or {@code "UNASSIGNED"}
 */
public record NodeAllocation(
        Integer shards,
        Long diskIndicesBytes,
        Long diskUsedBytes,
        Long diskAvailBytes,
        Integer diskPercent,
        String node) {

    public boolean isUnassignedRow() {
        return "UNASSIGNED".equalsIgnoreCase(node);
    }
}