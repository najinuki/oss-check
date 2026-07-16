package com.nj.oss.check.snapshot;

/**
 * Parsed {@code _cluster/allocation/explain} response for the shard the
 * cluster chose to explain. Only present when the cluster had an unassigned
 * shard at collection time.
 *
 * @param index               index of the explained shard
 * @param shard               shard number
 * @param primary             whether the explained shard is a primary
 * @param currentState        e.g. {@code "unassigned"}
 * @param unassignedInfo      why the shard became unassigned (may be null for assigned shards)
 * @param canAllocate         decision, e.g. {@code "no"} / {@code "yes"} / {@code "throttled"}
 * @param allocateExplanation human-readable explanation of the decision
 */
public record AllocationExplain(
        String index,
        int shard,
        boolean primary,
        String currentState,
        UnassignedInfo unassignedInfo,
        String canAllocate,
        String allocateExplanation) {

    public record UnassignedInfo(String reason, String at, String details) {
    }
}