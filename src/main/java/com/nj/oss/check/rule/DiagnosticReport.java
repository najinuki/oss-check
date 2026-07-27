package com.nj.oss.check.rule;

import java.util.List;

/**
 * Everything one diagnose run produced: the rules that fired, and the rules
 * that could not be evaluated.
 *
 * <p>The exit code is decided by {@link #findings} alone. A skipped rule is not
 * an execution error, so it never changes the exit code — scripts and cron jobs
 * keep the {@code 0} = clean / {@code 1} = findings contract. It is surfaced in
 * the report body instead.
 *
 * @param findings rules that fired, most severe first (ties broken by rule id)
 * @param skipped  rules that could not run, by rule id
 */
public record DiagnosticReport(List<Finding> findings, List<SkippedRule> skipped) {

    public DiagnosticReport {
        findings = List.copyOf(findings);
        skipped = List.copyOf(skipped);
    }

    public boolean hasFindings() {
        return !findings.isEmpty();
    }

    public boolean hasSkipped() {
        return !skipped.isEmpty();
    }
}
