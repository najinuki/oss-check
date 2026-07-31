package com.nj.oss.check.cli;

/** Output shape of a diagnose run (DESIGN.md 3.2). */
public enum ReportFormat {

    /** For a person reading a terminal. */
    TEXT,

    /** For a script. Stable field names, no decoration. */
    JSON
}
