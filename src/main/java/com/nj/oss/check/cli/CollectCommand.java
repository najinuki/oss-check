package com.nj.oss.check.cli;

import com.nj.oss.check.collect.ClusterConnection;
import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.HttpDumpSource;
import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.collect.TarGzDumpWriter;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Callable;

/**
 * Collects every API response from a live cluster into a tar.gz dump
 * (DESIGN.md 3.1). The operator supplies connection details; which endpoints
 * to call is not their problem.
 */
@Component
@Command(
        name = "collect",
        description = "Collect API responses from a cluster into a tar.gz dump.",
        mixinStandardHelpOptions = true)
public class CollectCommand implements Callable<Integer> {

    /** UTC and second precision: sorts chronologically and is legal in a file name everywhere. */
    private static final DateTimeFormatter DUMP_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    @Option(names = "--endpoint", required = true, paramLabel = "<url>",
            description = "Cluster base URL, e.g. https://opensearch.internal:9200")
    URI endpoint;

    @Option(names = "--user", paramLabel = "<name>",
            description = "Basic-auth user. The password comes from $"
                    + PasswordSource.ENVIRONMENT_VARIABLE + " or is asked for interactively.")
    String user;

    @Option(names = "--insecure",
            description = "Accept a TLS certificate this host does not trust, typically self-signed. "
                    + "The certificate must still match the endpoint's host name.")
    boolean insecure;

    @Option(names = "--output", paramLabel = "<path>",
            description = "Where to write the dump. Defaults to oss-check-<timestamp>.tar.gz "
                    + "in the current directory. An existing file is never overwritten.")
    Path output;

    @Spec
    CommandSpec spec;

    /** Replaced in tests. Not worth a Spring bean: nothing else needs it. */
    PasswordSource passwordSource = new PasswordSource();

    @Override
    public Integer call() throws Exception {
        Path target = output != null ? output : defaultOutput(Instant.now());
        // Fail here rather than after a minute of requests. This is courtesy,
        // not the guarantee — TarGzDumpWriter is what actually refuses to
        // replace a dump, atomically, at the moment it creates the file.
        if (Files.exists(target)) {
            throw new IllegalStateException(alreadyExists(target));
        }

        String password = user == null ? null : passwordSource.forUser(user);
        ClusterConnection connection = new ClusterConnection(endpoint, user, password, insecure);

        RawDump dump = new HttpDumpSource(connection, ToolVersion.VERSION).load();
        try {
            new TarGzDumpWriter().write(dump, target);
        } catch (FileAlreadyExistsException e) {
            // Something created the file while we were collecting.
            throw new IllegalStateException(alreadyExists(target), e);
        }
        report(dump, target);
        return ExitCode.NO_FINDINGS;
    }

    private static String alreadyExists(Path target) {
        return target + " already exists. A dump is evidence, so it is not overwritten.";
    }

    static Path defaultOutput(Instant startedAt) {
        return Path.of("oss-check-" + DUMP_TIMESTAMP.format(startedAt) + ".tar.gz");
    }

    /**
     * A partial collection is a normal result, so what is missing is said out
     * loud rather than left for whoever opens the dump to discover.
     */
    private void report(RawDump dump, Path target) {
        int total = CollectTarget.values().length;
        int collected = dump.payloads().size();
        spec.commandLine().getOut()
                .printf("Wrote %s (%d of %d targets collected)%n", target, collected, total);
        if (collected == total) {
            return;
        }
        PrintWriter err = spec.commandLine().getErr();
        err.printf("%d target(s) could not be collected; metadata.json in the dump says why:%n",
                total - collected);
        for (CollectTarget missing : CollectTarget.values()) {
            if (!dump.payloads().containsKey(missing)) {
                err.println("  " + missing.fileName());
            }
        }
    }
}
