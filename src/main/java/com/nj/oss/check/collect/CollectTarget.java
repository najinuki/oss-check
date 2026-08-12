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

    CLUSTER_HEALTH("_cluster/health", "cluster_health.json", Requirement.REQUIRED, Cadence.PER_SAMPLE),
    NODES_STATS("_nodes/stats", "nodes_stats.json", Requirement.REQUIRED, Cadence.PER_SAMPLE),
    CLUSTER_SETTINGS("_cluster/settings?include_defaults=true", "cluster_settings.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    ALLOCATION_EXPLAIN("_cluster/allocation/explain", "allocation_explain.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CAT_SHARDS("_cat/shards?format=json&bytes=b", "cat_shards.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CAT_INDICES("_cat/indices?format=json&bytes=b", "cat_indices.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CAT_ALLOCATION("_cat/allocation?format=json&bytes=b", "cat_allocation.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CLUSTER_PENDING_TASKS("_cluster/pending_tasks", "cluster_pending_tasks.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CLUSTER_STATS("_cluster/stats", "cluster_stats.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CAT_NODES("_cat/nodes?format=json&full_id=true&bytes=b", "cat_nodes.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CAT_RECOVERY("_cat/recovery?format=json&active_only=true&bytes=b", "cat_recovery.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    CAT_SEGMENTS("_cat/segments?format=json&bytes=b", "cat_segments.json", Requirement.OPTIONAL, Cadence.SHARED),
    CAT_PLUGINS("_cat/plugins?format=json", "cat_plugins.json", Requirement.OPTIONAL, Cadence.SHARED),
    CAT_FIELDDATA("_cat/fielddata?format=json&bytes=b", "cat_fielddata.json", Requirement.OPTIONAL, Cadence.PER_SAMPLE),
    INDEX_TEMPLATES("_index_template", "index_templates.json", Requirement.OPTIONAL, Cadence.SHARED);

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

    /**
     * How often {@code collect} requests this target when taking several samples
     * over an observation window.
     *
     * <p>The question is not "can this value change?" but <b>"does the diagnosis
     * change if it does, and can we afford to ask again?"</b> Judged the first
     * way, {@code _cluster/settings} would be shared — yet it is the thing an
     * operator most often changes mid-incident, and OSC-003 exists precisely
     * because someone changed a setting and forgot.
     *
     * <p>So {@link #PER_SAMPLE} is the default and {@link #SHARED} is an
     * exception granted on one of two distinct grounds:
     *
     * <ul>
     *   <li><b>Structurally static</b> — {@code _index_template},
     *       {@code _cat/plugins}. These describe how the cluster is built, not
     *       what it is doing.</li>
     *   <li><b>Too slow to matter, too big to repeat</b> — {@code _cat/segments}.
     *       Segment counts do move with indexing and merges, so this is not
     *       static; but "too many segments" accumulates over hours, not over a
     *       one-minute window, and this is the largest response collected.
     *       Revisit if a rule ever needs the merge <i>rate</i> rather than the
     *       count.</li>
     * </ul>
     */
    public enum Cadence {
        /** Requested once per sample; stored under {@code collection/NN/}. */
        PER_SAMPLE,
        /**
         * Requested once for the whole dump and stored under {@code collection/},
         * shared by every sample's snapshot. Treating it as belonging to sample
         * 01 alone would leave later snapshots with an empty field, and a rule
         * needing it would report "no data" while the data is right there.
         */
        SHARED
    }

    /** Name of the metadata file collect writes alongside the API responses. */
    public static final String METADATA_FILE_NAME = "metadata.json";

    private final String path;
    private final String fileName;
    private final Requirement requirement;
    private final Cadence cadence;

    CollectTarget(String path, String fileName, Requirement requirement, Cadence cadence) {
        this.path = path;
        this.fileName = fileName;
        this.requirement = requirement;
        this.cadence = cadence;
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

    public Cadence cadence() {
        return cadence;
    }

    /** Whether one response serves every sample in the dump. */
    public boolean isShared() {
        return cadence == Cadence.SHARED;
    }
}