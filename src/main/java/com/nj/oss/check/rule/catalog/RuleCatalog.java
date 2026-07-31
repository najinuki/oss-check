package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.rule.DiagnosticRule;

import java.util.List;

/**
 * Every rule this build ships. Adding a rule means adding a line here — there
 * is no scanning, no registry, and no configuration file (DESIGN.md 4.1).
 *
 * <p>Kept next to the rules rather than in the CLI so that the catalog stays
 * Spring-free like the rest of {@code rule}. The CLI asks for the list; it does
 * not assemble it.
 */
public final class RuleCatalog {

    private RuleCatalog() {
    }

    public static List<DiagnosticRule> all() {
        return List.of(
                new CircuitBreakerTrippingRule(),
                new ShardLimitRule(),
                new AllocationDisabledRule());
    }
}
