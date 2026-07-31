package com.nj.oss.check.cli;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.collect.TarGzDumpWriter;
import com.nj.oss.check.rule.DiagnosticRule;
import com.nj.oss.check.rule.Evidence;
import com.nj.oss.check.rule.Finding;
import com.nj.oss.check.rule.RuleResult;
import com.nj.oss.check.rule.Severity;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.testsupport.Fixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DiagnoseCommandTest {

    private static final Finding BREAKER_TRIPPING = new Finding(
            "OSC-001",
            Severity.CRITICAL,
            "Parent circuit breaker is tripping under heap pressure",
            List.of(new Evidence("nodes.stats.breakers.parent.tripped", "847")),
            "Reduce the query load or raise the breaker limit.");

    @TempDir
    Path workDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private Path dump;

    @BeforeEach
    void writeDump() throws IOException {
        dump = workDir.resolve("dump.tar.gz");
        new TarGzDumpWriter().write(Fixtures.load("normal"), dump);
    }

    @Test
    void aCleanClusterReportsNothingAndExitsZero() {
        int exitCode = execute(command());

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(out.toString())
                .contains("fixture-cluster", "No findings.");
    }

    @Test
    void aBuildWithoutRulesSaysSoRatherThanLookingClean() {
        // an empty report from an empty catalog is not a healthy cluster
        execute(command());

        assertThat(err.toString()).contains("No diagnostic rules are registered");
    }

    @Test
    void aFiredRuleIsReportedWithItsEvidenceAndExitsOne() {
        int exitCode = execute(command(rule -> RuleResult.fired(BREAKER_TRIPPING)));

        assertThat(exitCode).isEqualTo(ExitCode.FINDINGS);
        assertThat(out.toString())
                .contains("CRITICAL", "OSC-001", "Parent circuit breaker is tripping")
                .contains("nodes.stats.breakers.parent.tripped = 847")
                .contains("Reduce the query load")
                .contains("1 finding");
    }

    @Test
    void aSkippedRuleIsShownButDoesNotChangeTheExitCode() {
        // scripts branch on findings only; missing data is not a finding
        int exitCode = execute(command(rule -> RuleResult.skipped(
                "requires cluster_settings.json (collection failed: HTTP 403)")));

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(out.toString())
                .contains("No findings.")
                .contains("SKIPPED (1 rule could not be evaluated)")
                .contains("OSC-001  requires cluster_settings.json (collection failed: HTTP 403)");
    }

    @Test
    void theJsonReportCarriesFindingsAndSkippedRules() {
        int exitCode = execute(command(
                stubRule("OSC-001", rule -> RuleResult.fired(BREAKER_TRIPPING)),
                stubRule("OSC-002", rule -> RuleResult.skipped("requires cat_shards.json (not in dump)"))),
                "--format", "json");

        assertThat(exitCode).isEqualTo(ExitCode.FINDINGS);
        JsonNode report = JsonMapper.builder().build().readTree(out.toString());
        assertThat(report.path("cluster").path("name").asString()).isEqualTo("fixture-cluster");
        assertThat(report.path("cluster").path("collectedAt").asString()).isNotEmpty();
        assertThat(report.path("findings").size()).isEqualTo(1);
        assertThat(report.path("findings").get(0).path("ruleId").asString()).isEqualTo("OSC-001");
        assertThat(report.path("findings").get(0).path("severity").asString()).isEqualTo("CRITICAL");
        assertThat(report.path("findings").get(0).path("evidence").get(0).path("value").asString())
                .isEqualTo("847");
        assertThat(report.path("skipped").get(0).path("ruleId").asString()).isEqualTo("OSC-002");
    }

    @Test
    void aMissingDumpIsAnExecutionError() {
        int exitCode = execute(command(), "--input", workDir.resolve("absent.tar.gz").toString());

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("No readable dump at");
    }

    @Test
    void aDumpMissingRequiredDataFailsLoudlyInsteadOfDiagnosingNothing() throws IOException {
        Path broken = workDir.resolve("broken.tar.gz");
        new TarGzDumpWriter().write(withoutClusterHealth(Fixtures.load("normal")), broken);

        int exitCode = execute(command(), "--input", broken.toString());

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(out.toString()).doesNotContain("No findings.");
    }

    private static RawDump withoutClusterHealth(RawDump dump) {
        Map<CollectTarget, String> payloads = new EnumMap<>(dump.payloads());
        payloads.remove(CollectTarget.CLUSTER_HEALTH);
        return new RawDump(dump.metadataJson(), payloads);
    }

    private DiagnoseCommand command(DiagnosticRule... rules) {
        DiagnoseCommand command = new DiagnoseCommand();
        command.rules = List.of(rules);
        return command;
    }

    private DiagnoseCommand command(Evaluation evaluation) {
        return command(stubRule("OSC-001", evaluation));
    }

    private static DiagnosticRule stubRule(String id, Evaluation evaluation) {
        return new DiagnosticRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public Severity severity() {
                return Severity.CRITICAL;
            }

            @Override
            public RuleResult evaluate(ClusterSnapshot snapshot) {
                return evaluation.evaluate(snapshot);
            }
        };
    }

    private int execute(DiagnoseCommand command, String... args) {
        String[] all = args.length == 0
                ? new String[]{"--input", dump.toString()}
                : concat(args);
        return new CommandLine(command)
                .setExecutionExceptionHandler(new ExecutionErrorHandler())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(all);
    }

    private String[] concat(String... args) {
        boolean hasInput = List.of(args).contains("--input");
        if (hasInput) {
            return args;
        }
        String[] all = new String[args.length + 2];
        all[0] = "--input";
        all[1] = dump.toString();
        System.arraycopy(args, 0, all, 2, args.length);
        return all;
    }

    @FunctionalInterface
    private interface Evaluation {
        RuleResult evaluate(ClusterSnapshot snapshot);
    }
}
