package com.nj.oss.check.cli;

/**
 * Process exit codes (DESIGN.md 3.2).
 *
 * <p>Scripts and cron jobs branch on these, so they are part of the tool's
 * public contract: a code never changes meaning, and no new code is added
 * without a design decision.
 */
public final class ExitCode {

    /** Ran to completion, nothing to report. */
    public static final int NO_FINDINGS = 0;

    /**
     * Ran to completion and reported at least one finding. Rules that could not
     * be evaluated do not reach this code — data being missing is not a finding
     * (DESIGN.md 3.2).
     */
    public static final int FINDINGS = 1;

    /**
     * Could not run at all: bad arguments, unreachable cluster, missing
     * credentials, unreadable dump. Never means "the cluster is unhealthy".
     */
    public static final int ERROR = 2;

    private ExitCode() {
    }
}
