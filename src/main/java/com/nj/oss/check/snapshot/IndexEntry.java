package com.nj.oss.check.snapshot;

/**
 * One row of {@code _cat/indices?format=json&bytes=b}.
 *
 * @param health         {@code "green"} / {@code "yellow"} / {@code "red"}; null for closed indices
 * @param status         {@code "open"} / {@code "close"}
 * @param index          index name
 * @param pri            number of primary shards
 * @param rep            number of replicas per primary
 * @param docsCount      document count; null for closed indices
 * @param storeSizeBytes total store size in bytes (primaries + replicas); null for closed indices
 */
public record IndexEntry(
        String health,
        String status,
        String index,
        Integer pri,
        Integer rep,
        Long docsCount,
        Long storeSizeBytes) {
}