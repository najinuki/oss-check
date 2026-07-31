package com.nj.oss.check.cli;

import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.collect.TarGzDumpSource;
import com.nj.oss.check.rule.DiagnosticReport;
import com.nj.oss.check.rule.DiagnosticRule;
import com.nj.oss.check.rule.RuleEngine;
import com.nj.oss.check.rule.catalog.RuleCatalog;
import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.SnapshotMetadata;
import com.nj.oss.check.snapshot.parse.ClusterSnapshotParser;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Runs the rule engine against a dump (DESIGN.md 3.2).
 *
 * <p>Live mode ({@code --endpoint}) is not wired yet; it reuses the same
 * pipeline with {@code HttpDumpSource} in place of the archive reader.
 */
@Component
@Command(
        name = "diagnose",
        description = "Run the diagnostic rules against a dump.",
        mixinStandardHelpOptions = true)
public class DiagnoseCommand implements Callable<Integer> {

    @Option(names = "--input", required = true, paramLabel = "<dump.tar.gz>",
            description = "Dump to diagnose, as written by collect.")
    Path input;

    @Option(names = "--format", paramLabel = "<text|json>", defaultValue = "TEXT",
            description = "Report format. Defaults to text.")
    ReportFormat format;

    @Spec
    CommandSpec spec;

    /** Replaced in tests to drive one rule at a time. */
    List<DiagnosticRule> rules = RuleCatalog.all();

    @Override
    public Integer call() throws Exception {
        if (!Files.isReadable(input)) {
            throw new IllegalStateException("No readable dump at " + input);
        }

        RawDump dump = new TarGzDumpSource(input).load();
        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(dump);
        PrintWriter err = spec.commandLine().getErr();
        warnAboutWhatThisRunCannotSee(err, snapshot.metadata());

        DiagnosticReport report = new RuleEngine(rules).run(snapshot);
        PrintWriter out = spec.commandLine().getOut();
        if (format == ReportFormat.JSON) {
            out.println(ReportRenderer.json(snapshot.metadata(), report));
        } else {
            ReportRenderer.text(out, snapshot.metadata(), report);
        }

        // Skipped rules deliberately do not reach the exit code: data being
        // missing is not a finding (DESIGN.md 3.2).
        return report.hasFindings() ? ExitCode.FINDINGS : ExitCode.NO_FINDINGS;
    }

    /**
     * Both of these mean "this report is narrower than it looks", and staying
     * quiet about either would let an empty report read as a clean cluster.
     */
    private void warnAboutWhatThisRunCannotSee(PrintWriter err, SnapshotMetadata metadata) {
        if (rules.isEmpty()) {
            err.println("No diagnostic rules are registered in this build; nothing can be reported.");
        }
        if (metadata.isNewerThanSupported()) {
            err.printf("This dump was written by a newer oss-check (dump schema %d, this build knows %d); "
                            + "reading what is recognised and ignoring the rest.%n",
                    metadata.dumpSchemaVersion(), SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION);
        }
    }
}
