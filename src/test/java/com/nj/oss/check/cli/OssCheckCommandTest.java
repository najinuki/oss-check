package com.nj.oss.check.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The command tree is exercised without Spring: picocli's own factory can build
 * these commands, and keeping the test that way means a broken context cannot
 * be mistaken for a broken command line.
 */
class OssCheckCommandTest {

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    @Test
    void listsBothCommandsInItsHelp() {
        int exitCode = execute("--help");

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(out.toString()).contains("oss-check", "collect", "diagnose");
    }

    @Test
    void withoutASubcommandItShowsUsageAndFails() {
        // doing nothing quietly would look like a successful run to a script
        int exitCode = execute();

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("Usage:", "collect", "diagnose");
    }

    @Test
    void anUnknownOptionIsAUsageError() {
        int exitCode = execute("--nonsense");

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("Unknown option");
    }

    @Test
    void versionIsReported() {
        int exitCode = execute("--version");

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(out.toString()).contains("oss-check", ToolVersion.VERSION);
    }

    @Test
    void theShippedCommandTreeMapsExceptionsToTheErrorExitCode() {
        // the wiring itself is the contract: without this handler a crash
        // leaves exit code 1, which means "findings reported" (DESIGN.md 3.2)
        assertThat(OssCheckCommand.commandLine(CommandLine.defaultFactory()).getExecutionExceptionHandler())
                .isInstanceOf(ExecutionErrorHandler.class);
    }

    private int execute(String... args) {
        return OssCheckCommand.commandLine(CommandLine.defaultFactory())
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }
}
