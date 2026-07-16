package com.nj.oss.check.snapshot;

/**
 * One row of {@code _cat/shards?format=json&bytes=b}.
 *
 * @param index      index name
 * @param shard      shard number
 * @param prirep     {@code "p"} (primary) or {@code "r"} (replica)
 * @param state      e.g. {@code "STARTED"}, {@code "UNASSIGNED"}, {@code "INITIALIZING"}, {@code "RELOCATING"}
 * @param docs       document count; null when the shard is not assigned
 * @param storeBytes on-disk size in bytes; null when the shard is not assigned
 * @param node       node name holding the shard; null when unassigned
 */
public record ShardEntry(
        String index,
        int shard,
        String prirep,
        String state,
        Long docs,
        Long storeBytes,
        String node) {

    public boolean isPrimary() {
        return "p".equals(prirep);
    }

    public boolean isUnassigned() {
        return "UNASSIGNED".equalsIgnoreCase(state);
    }
}