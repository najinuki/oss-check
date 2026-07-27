package com.nj.oss.check.rule;

import java.util.Objects;

/**
 * A rule that could not be evaluated, and why. Reports must show these: a rule
 * that was never able to run is not the same as a rule that found nothing.
 *
 * @param ruleId rule identifier, e.g. {@code "OSC-002"}
 * @param reason human-readable cause, e.g.
 *               {@code "requires cluster_settings.json (collection failed: HTTP 403)"}
 */
public record SkippedRule(String ruleId, String reason) {

    public SkippedRule {
        Objects.requireNonNull(ruleId, "ruleId");
        Objects.requireNonNull(reason, "reason");
    }
}
