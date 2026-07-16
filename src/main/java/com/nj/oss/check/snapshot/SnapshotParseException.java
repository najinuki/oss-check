package com.nj.oss.check.snapshot;

/** Thrown when a dump is missing required data or contains unparseable JSON. */
public class SnapshotParseException extends RuntimeException {

    public SnapshotParseException(String message) {
        super(message);
    }

    public SnapshotParseException(String message, Throwable cause) {
        super(message, cause);
    }
}