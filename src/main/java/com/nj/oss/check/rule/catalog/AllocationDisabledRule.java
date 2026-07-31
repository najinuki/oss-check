package com.nj.oss.check.rule.catalog;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.rule.DiagnosticRule;
import com.nj.oss.check.rule.Evidence;
import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.snapshot.AllocationExplain;
import com.nj.oss.check.snapshot.ClusterSettings;
import com.nj.oss.check.snapshot.ClusterSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * OSC-003 — shard allocation is switched off cluster-wide.
 *
 * <p>{@code cluster.routing.allocation.enable: none} is a legitimate thing to
 * set for the length of a rolling restart, and a damaging thing to leave set:
 * the cluster stops healing. Nothing breaks at the moment it is set, so it is
 * routinely forgotten and only discovered when a node leaves and its shards
 * never come back.
 *
 * <p>Read from the explicit scopes only. The default is {@code all}, so a value
 * of {@code none} is always something an operator did (결정 4).
 */
public final class AllocationDisabledRule implements DiagnosticRule {

    static final String ALLOCATION_ENABLE = "cluster.routing.allocation.enable";
    static final String DISABLED = "none";

    @Override
    public String id() {
        return "OSC-003";
    }

    @Override
    public Severity severity() {
        return Severity.CRITICAL;
    }

    @Override
    public RuleResult evaluate(ClusterSnapshot snapshot) {
        Optional<ClusterSettings> settings = snapshot.settings();
        if (settings.isEmpty()) {
            return RuleResult.skipped(snapshot.absenceReason(CollectTarget.CLUSTER_SETTINGS));
        }

        // The effective value decides whether to fire: a transient "all" over a
        // persistent "none" means allocation is on, and reporting it would be a
        // false positive.
        Optional<String> configured = settings.get().explicit(ALLOCATION_ENABLE);
        if (configured.filter(DISABLED::equalsIgnoreCase).isEmpty()) {
            return RuleResult.notFired();
        }
        List<String> scopes = disabledScopes(settings.get());

        int unassigned = snapshot.health().unassignedShards();
        List<Evidence> evidence = new ArrayList<>();
        for (String scope : scopes) {
            evidence.add(new Evidence("cluster.settings." + scope + "." + ALLOCATION_ENABLE, DISABLED));
        }
        evidence.add(new Evidence("cluster.health.status", snapshot.health().status().name()));
        evidence.add(new Evidence("cluster.health.unassigned_shards", String.valueOf(unassigned)));
        snapshot.allocationExplain().ifPresent(explain -> evidence.addAll(explainEvidence(explain)));

        return RuleResult.fired(new Finding(
                id(),
                severity(),
                summary(unassigned),
                evidence,
                recommendation(scopes)));
    }

    /**
     * Which scopes actually hold {@code none}. Clearing the wrong one leaves
     * allocation off and the operator believing they turned it back on — the
     * transient scope is the common one during a rolling restart, and it wins
     * over persistent. Both are cleared when both are set, since removing only
     * the transient value would let a persistent {@code none} take over.
     */
    private static List<String> disabledScopes(ClusterSettings settings) {
        List<String> scopes = new ArrayList<>();
        if (DISABLED.equalsIgnoreCase(settings.transientSettings().get(ALLOCATION_ENABLE))) {
            scopes.add("transient");
        }
        if (DISABLED.equalsIgnoreCase(settings.persistentSettings().get(ALLOCATION_ENABLE))) {
            scopes.add("persistent");
        }
        return scopes;
    }

    private static String recommendation(List<String> scopes) {
        String body = scopes.stream()
                .map(scope -> "\"" + scope + "\":{\"" + ALLOCATION_ENABLE + "\":null}")
                .collect(Collectors.joining(","));
        return "If a rolling restart is over, turn allocation back on: "
                + "PUT _cluster/settings {" + body + "}. "
                + "Setting it to null restores the default (all) instead of pinning it.";
    }

    /**
     * The same misconfiguration, at two very different stages. Saying which one
     * this cluster is in is the difference between a chore and an incident.
     */
    private static String summary(int unassigned) {
        if (unassigned > 0) {
            return "Shard allocation is disabled cluster-wide and " + unassigned
                    + " shard(s) are unassigned; they cannot be assigned while it stays off";
        }
        return "Shard allocation is disabled cluster-wide; nothing is unassigned yet, "
                + "but the cluster cannot heal from the next node it loses";
    }

    private static List<Evidence> explainEvidence(AllocationExplain explain) {
        List<Evidence> evidence = new ArrayList<>();
        evidence.add(new Evidence("allocation.explain.index",
                explain.index() + "[" + explain.shard() + "]"));
        if (explain.canAllocate() != null) {
            evidence.add(new Evidence("allocation.explain.can_allocate", explain.canAllocate()));
        }
        if (explain.allocateExplanation() != null) {
            evidence.add(new Evidence("allocation.explain.allocate_explanation",
                    explain.allocateExplanation()));
        }
        return evidence;
    }
}
