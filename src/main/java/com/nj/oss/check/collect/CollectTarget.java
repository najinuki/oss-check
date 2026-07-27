package com.nj.oss.check.collect;

/**
 * The list of APIs {@code collect} gathers, and the file name each response is
 * stored under inside the dump archive. Shared by the live collector and the
 * offline archive reader so both produce identical dumps.
 *
 * <p>_cat endpoints request {@code bytes=b} so sizes arrive as plain numbers
 * instead of human-readable strings.
 *
 * <p>This list is expected to grow. Every target carries a {@link Requirement}
 * so that growth cannot break dumps taken by older versions: <b>a new target is
 * always {@link Requirement#OPTIONAL}</b>. Promoting one to
 * {@link Requirement#REQUIRED} makes older dumps unreadable and is therefore a
 * dump schema version change (see {@code SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION}).
 */
public enum CollectTarget {

    CLUSTER_HEALTH("_cluster/health", "cluster_health.json", Requirement.REQUIRED),
    NODES_STATS("_nodes/stats", "nodes_stats.json", Requirement.REQUIRED),
    CLUSTER_SETTINGS("_cluster/settings?include_defaults=true", "cluster_settings.json", Requirement.OPTIONAL),
    ALLOCATION_EXPLAIN("_cluster/allocation/explain", "allocation_explain.json", Requirement.OPTIONAL),
    CAT_SHARDS("_cat/shards?format=json&bytes=b", "cat_shards.json", Requirement.OPTIONAL),
    CAT_INDICES("_cat/indices?format=json&bytes=b", "cat_indices.json", Requirement.OPTIONAL),
    CAT_ALLOCATION("_cat/allocation?format=json&bytes=b", "cat_allocation.json", Requirement.OPTIONAL);

    /**
     * Whether a dump is usable at all without this target.
     *
     * <p>Only data without which no rule could run is REQUIRED. Everything else
     * is OPTIONAL: a partial collection (permission denied, timeout, an API a
     * given cluster version does not expose) must still produce a diagnosable
     * dump, with the rules that needed the missing data reported as skipped
     * rather than silently not firing.
     */
    public enum Requirement {
        REQUIRED,
        OPTIONAL
    }

    /** Name of the metadata file collect writes alongside the API responses. */
    public static final String METADATA_FILE_NAME = "metadata.json";

    private final String path;
    private final String fileName;
    private final Requirement requirement;

    CollectTarget(String path, String fileName, Requirement requirement) {
        this.path = path;
        this.fileName = fileName;
        this.requirement = requirement;
    }

    /** Request path relative to the cluster endpoint, including query string. */
    public String path() {
        return path;
    }

    /** File name inside the dump archive. */
    public String fileName() {
        return fileName;
    }

    public Requirement requirement() {
        return requirement;
    }

    public boolean isRequired() {
        return requirement == Requirement.REQUIRED;
    }
}