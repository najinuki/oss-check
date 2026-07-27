package com.nj.oss.check.collect;

import java.util.Optional;

/**
 * What happened when {@code collect} requested one target, recorded in the
 * dump's {@code metadata.json}.
 *
 * <p>A dump is routinely read months later, somewhere else, by someone who was
 * not present at collection time. Without this record there is no way to tell
 * "the cluster had no such data" from "we were not allowed to read it", and
 * therefore no way to explain why a rule was skipped.
 *
 * @param target     the target this outcome describes; null only when read from
 *                   a dump that names a target this version does not know, in
 *                   which case {@code SnapshotMetadata} drops the entry
 * @param status     whether the response was stored in the dump
 * @param httpStatus HTTP status returned by the cluster, null if the request
 *                   never got a response (connection failure, timeout)
 * @param message    failure detail, null when the collection succeeded
 */
public record CollectionOutcome(
        CollectTarget target,
        Status status,
        Integer httpStatus,
        String message) {

    public enum Status {
        /** Response stored in the dump. */
        OK,
        /** Request failed or returned an error; the file is absent from the dump. */
        FAILED,
        /** Status written by a newer version of the tool that this one does not know. */
        UNKNOWN
    }

    public CollectionOutcome {
        // Nothing here is rejected for being null: a dump written by a newer
        // tool may name a target or a status this version has no constant for,
        // and the mapper maps those to null rather than failing. Forward
        // compatibility is decided at the metadata level, not here.
        status = status == null ? Status.UNKNOWN : status;
    }

    public static CollectionOutcome ok(CollectTarget target, int httpStatus) {
        return new CollectionOutcome(target, Status.OK, httpStatus, null);
    }

    public static CollectionOutcome failed(CollectTarget target, Integer httpStatus, String message) {
        return new CollectionOutcome(target, Status.FAILED, httpStatus, message);
    }

    public boolean isOk() {
        return status == Status.OK;
    }

    /**
     * Human-readable failure detail for reports, e.g.
     * {@code "HTTP 403: no permissions for [indices:monitor/stats]"}.
     * Empty when this outcome is not a failure.
     */
    public Optional<String> describeFailure() {
        if (isOk()) {
            return Optional.empty();
        }
        StringBuilder description = new StringBuilder();
        if (httpStatus != null) {
            description.append("HTTP ").append(httpStatus);
        }
        if (message != null && !message.isBlank()) {
            if (!description.isEmpty()) {
                description.append(": ");
            }
            description.append(message);
        }
        return description.isEmpty() ? Optional.empty() : Optional.of(description.toString());
    }
}
