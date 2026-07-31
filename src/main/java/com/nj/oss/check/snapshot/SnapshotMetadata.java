package com.nj.oss.check.snapshot;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.CollectionOutcome;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Metadata written by {@code collect} into the dump ({@code metadata.json}).
 *
 * @param dumpSchemaVersion layout version of the dump archive; boxed only
 *                          because a dump written before the field existed has
 *                          no value for it — never null after construction
 * @param collectedAt       when the dump was taken (UTC)
 * @param toolVersion       version of the tool that produced the dump
 * @param clusterName       cluster name at collection time, null when the
 *                          cluster could not be identified
 * @param clusterVersion    OpenSearch version reported by the cluster root endpoint
 * @param identityFailure   why the cluster could not be identified, null when it
 *                          could. The root endpoint is not a {@link CollectTarget},
 *                          so a failure there has nowhere else to be recorded —
 *                          and "a dump of nothing in particular" is worth
 *                          explaining to whoever opens it later (DESIGN.md 3.1)
 * @param collection        per-target collection outcome; entries referring to
 *                          targets this version does not know are dropped
 */
public record SnapshotMetadata(
        Integer dumpSchemaVersion,
        Instant collectedAt,
        String toolVersion,
        String clusterName,
        String clusterVersion,
        String identityFailure,
        List<CollectionOutcome> collection) {

    /**
     * Bumped only for changes that make a dump unreadable by an older reader —
     * in practice, promoting a target to {@code REQUIRED} or renaming files.
     * Adding an OPTIONAL target does not bump it.
     */
    public static final int CURRENT_DUMP_SCHEMA_VERSION = 1;

    public SnapshotMetadata {
        // Absent means a dump written before the field existed; those use the
        // version 1 layout.
        if (dumpSchemaVersion == null || dumpSchemaVersion <= 0) {
            dumpSchemaVersion = 1;
        }
        collection = collection == null
                ? List.of()
                : List.copyOf(dropUnknownTargets(collection));
    }

    /**
     * True when the dump was written by a tool that knows a newer archive
     * layout. The dump is still read on a best-effort basis; the CLI layer
     * decides how to surface the warning.
     */
    public boolean isNewerThanSupported() {
        return dumpSchemaVersion > CURRENT_DUMP_SCHEMA_VERSION;
    }

    /** Whether the cluster this dump came from could be named at all. */
    public boolean isIdentified() {
        return identityFailure == null;
    }

    /** Collection outcome for one target, empty when the dump records none. */
    public Optional<CollectionOutcome> outcomeOf(CollectTarget target) {
        return collection.stream()
                .filter(outcome -> outcome.target() == target)
                .findFirst();
    }

    /**
     * A newer tool may record targets this version has no constant for; the
     * mapper maps those to a null target. Such an entry cannot be attributed to
     * anything here, so it is dropped rather than kept as a null hole.
     */
    private static List<CollectionOutcome> dropUnknownTargets(List<CollectionOutcome> outcomes) {
        List<CollectionOutcome> known = new ArrayList<>(outcomes.size());
        for (CollectionOutcome outcome : outcomes) {
            if (outcome != null && outcome.target() != null) {
                known.add(outcome);
            }
        }
        return known;
    }
}
