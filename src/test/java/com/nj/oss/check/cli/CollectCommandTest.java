package com.nj.oss.check.cli;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.TarGzDumpSource;
import com.nj.oss.check.testsupport.FakeCluster;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class CollectCommandTest {

    @TempDir
    Path workDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private FakeCluster cluster;

    @BeforeEach
    void startCluster() {
        cluster = FakeCluster.serving("normal");
    }

    @AfterEach
    void stopCluster() {
        cluster.close();
    }

    @Test
    void writesADumpThatReadsBackAsADump() throws IOException {
        Path dump = workDir.resolve("dump.tar.gz");

        int exitCode = execute("--endpoint", cluster.endpoint(), "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(dump).exists();
        assertThat(out.toString()).contains("Wrote", dump.toString(), "15 of 15");
        // the file is only worth writing if the offline reader accepts it
        assertThat(new TarGzDumpSource(dump).load().payloads())
                .hasSize(CollectTarget.values().length);
    }

    @Test
    void aPartialCollectionSaysWhatIsMissing() {
        cluster.failing(CollectTarget.CAT_INDICES, 403, "{\"error\":\"denied\"}");
        Path dump = workDir.resolve("dump.tar.gz");

        int exitCode = execute("--endpoint", cluster.endpoint(), "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(out.toString()).contains("14 of 15");
        assertThat(err.toString()).contains("cat_indices.json", "metadata.json");
    }

    @Test
    void anExistingDumpIsNotOverwritten() throws IOException {
        Path dump = Files.writeString(workDir.resolve("dump.tar.gz"), "an earlier collection");

        int exitCode = execute("--endpoint", cluster.endpoint(), "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("already exists");
        assertThat(dump).hasContent("an earlier collection");
    }

    @Test
    void aMissingPasswordStopsBeforeAnythingIsCollected() {
        Path dump = workDir.resolve("dump.tar.gz");
        CollectCommand command = new CollectCommand();
        command.connection.passwordSource = new PasswordSource(name -> null, null);

        int exitCode = execute(command,
                "--endpoint", cluster.endpoint(), "--user", "admin", "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains(PasswordSource.ENVIRONMENT_VARIABLE);
        assertThat(dump).doesNotExist();
    }

    @Test
    void theDefaultDumpNameCarriesTheCollectionTime() {
        Path name = CollectCommand.defaultOutput(Instant.parse("2026-07-31T04:11:00Z"));

        assertThat(name).hasFileName("oss-check-20260731T041100Z.tar.gz");
    }

    private int execute(String... args) {
        return execute(new CollectCommand(), args);
    }

    private int execute(CollectCommand command, String... args) {
        return new CommandLine(command)
                .setExecutionExceptionHandler(new ExecutionErrorHandler())
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }
}
