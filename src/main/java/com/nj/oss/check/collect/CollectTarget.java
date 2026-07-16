package com.nj.oss.check.collect;

/**
 * The fixed list of APIs {@code collect} gathers, and the file name each
 * response is stored under inside the dump archive. Shared by the live
 * collector and the offline archive reader so both produce identical dumps.
 *
 * <p>_cat endpoints request {@code bytes=b} so sizes arrive as plain numbers
 * instead of human-readable strings.
 */
public enum CollectTarget {

    CLUSTER_HEALTH("_cluster/health", "cluster_health.json"),
    CLUSTER_SETTINGS("_cluster/settings?include_defaults=true", "cluster_settings.json"),
    ALLOCATION_EXPLAIN("_cluster/allocation/explain", "allocation_explain.json"),
    NODES_STATS("_nodes/stats", "nodes_stats.json"),
    CAT_SHARDS("_cat/shards?format=json&bytes=b", "cat_shards.json"),
    CAT_INDICES("_cat/indices?format=json&bytes=b", "cat_indices.json"),
    CAT_ALLOCATION("_cat/allocation?format=json&bytes=b", "cat_allocation.json");

    /** Name of the metadata file collect writes alongside the API responses. */
    public static final String METADATA_FILE_NAME = "metadata.json";

    private final String path;
    private final String fileName;

    CollectTarget(String path, String fileName) {
        this.path = path;
        this.fileName = fileName;
    }

    /** Request path relative to the cluster endpoint, including query string. */
    public String path() {
        return path;
    }

    /** File name inside the dump archive. */
    public String fileName() {
        return fileName;
    }
}