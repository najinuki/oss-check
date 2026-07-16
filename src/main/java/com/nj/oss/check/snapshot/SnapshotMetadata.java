package com.nj.oss.check.snapshot;

import java.time.Instant;

/**
 * Metadata written by {@code collect} into the dump ({@code metadata.json}).
 *
 * @param collectedAt    when the dump was taken (UTC)
 * @param toolVersion    version of the tool that produced the dump
 * @param clusterName    cluster name at collection time
 * @param clusterVersion OpenSearch version reported by the cluster root endpoint
 */
public record SnapshotMetadata(
        Instant collectedAt,
        String toolVersion,
        String clusterName,
        String clusterVersion) {
}