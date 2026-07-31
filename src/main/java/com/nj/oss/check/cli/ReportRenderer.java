package com.nj.oss.check.cli;

import com.nj.oss.check.rule.DiagnosticReport;
import com.nj.oss.check.rule.Evidence;
import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.SkippedRule;
import com.nj.oss.check.snapshot.SnapshotMetadata;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.util.List;

/**
 * Turns a {@link DiagnosticReport} into what the operator sees.
 *
 * <p>Both formats always show the skipped rules. A rule that could not be
 * evaluated is not the same as a rule that found nothing, and a report that
 * hides the difference is how a tool quietly misses things (DESIGN.md 4.4).
 */
final class ReportRenderer {

    /** Widest severity name, so the finding text lines up in a column. */
    private static final int SEVERITY_WIDTH = 8;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ReportRenderer() {
    }

    static void text(PrintWriter out, SnapshotMetadata metadata, DiagnosticReport report) {
        out.println(header(metadata));
        out.println();

        for (Finding finding : report.findings()) {
            out.printf("%-" + SEVERITY_WIDTH + "s  %s  %s%n",
                    finding.severity(), finding.ruleId(), finding.finding());
            if (!finding.evidence().isEmpty()) {
                out.println("  evidence");
                for (Evidence evidence : finding.evidence()) {
                    out.println("    " + evidence.render());
                }
            }
            out.println("  recommendation");
            out.println("    " + finding.recommendation());
            out.println();
        }

        out.println(report.hasFindings()
                ? count(report.findings().size(), "finding", "findings")
                : "No findings.");

        if (report.hasSkipped()) {
            out.println();
            out.printf("SKIPPED (%s could not be evaluated)%n",
                    count(report.skipped().size(), "rule", "rules"));
            for (SkippedRule skipped : report.skipped()) {
                out.println("  " + skipped.ruleId() + "  " + skipped.reason());
            }
        }
    }

    static String json(SnapshotMetadata metadata, DiagnosticReport report) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(new JsonReport(
                new JsonCluster(
                        metadata.clusterName(),
                        metadata.clusterVersion(),
                        // Written as text rather than left to the mapper's date
                        // handling: this field is a script's contract.
                        metadata.collectedAt() == null ? null : metadata.collectedAt().toString(),
                        metadata.toolVersion()),
                report.findings(),
                report.skipped()));
    }

    private static String header(SnapshotMetadata metadata) {
        String name = metadata.clusterName() == null ? "unknown cluster" : metadata.clusterName();
        String version = metadata.clusterVersion() == null
                ? ""
                : " (OpenSearch " + metadata.clusterVersion() + ")";
        return name + version + " - collected " + metadata.collectedAt()
                + " by oss-check " + metadata.toolVersion();
    }

    private static String count(int size, String singular, String plural) {
        return size + " " + (size == 1 ? singular : plural);
    }

    private record JsonReport(JsonCluster cluster, List<Finding> findings, List<SkippedRule> skipped) {
    }

    private record JsonCluster(String name, String version, String collectedAt, String toolVersion) {
    }
}
