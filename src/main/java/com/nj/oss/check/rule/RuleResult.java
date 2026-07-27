package com.nj.oss.check.rule;

import java.util.Objects;

/**
 * Outcome of evaluating one rule. Three states, not two.
 *
 * <p>An {@code Optional<Finding>} cannot express the difference between "this
 * rule looked and found nothing wrong" and "this rule could not look because
 * the data it needs is not in the snapshot". Both would be empty, so a failed
 * collection would read as a clean bill of health — a silent false negative.
 * Since collect targets may legitimately be absent, that difference has to
 * survive all the way into the report.
 */
public sealed interface RuleResult {

    /** The rule fired. */
    record Fired(Finding finding) implements RuleResult {
        public Fired {
            Objects.requireNonNull(finding, "finding");
        }
    }

    /** The rule evaluated normally and its condition did not hold. */
    record NotFired() implements RuleResult {
    }

    /**
     * The rule could not be evaluated because data it needs is absent.
     *
     * @param reason human-readable cause, e.g.
     *               {@code "requires cluster_settings.json (collection failed: HTTP 403)"};
     *               {@code ClusterSnapshot.absenceReason} produces this form
     */
    record Skipped(String reason) implements RuleResult {
        public Skipped {
            Objects.requireNonNull(reason, "reason");
        }
    }

    RuleResult NOT_FIRED = new NotFired();

    static RuleResult fired(Finding finding) {
        return new Fired(finding);
    }

    static RuleResult notFired() {
        return NOT_FIRED;
    }

    static RuleResult skipped(String reason) {
        return new Skipped(reason);
    }
}
