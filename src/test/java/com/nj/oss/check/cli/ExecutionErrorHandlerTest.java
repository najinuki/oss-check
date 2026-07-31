package com.nj.oss.check.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionErrorHandlerTest {

    private final StringWriter err = new StringWriter();

    @Test
    void aFailureToRunIsNeverReportedAsAFinding() {
        // an unreachable cluster reaching exit code 1 would tell a script the
        // cluster was diagnosed and found wanting
        int exitCode = execute(new Failing(new IOException("could not reach https://prod:9200")));

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("could not reach https://prod:9200");
    }

    @Test
    void anExceptionWithoutAMessageStillSaysSomething() {
        int exitCode = execute(new Failing(new NullPointerException()));

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("NullPointerException");
    }

    @Test
    void theStackTraceIsNotDumpedOnTheOperator() {
        execute(new Failing(new IOException("connection refused")));

        assertThat(err.toString()).doesNotContain("at com.nj.oss.check");
    }

    private int execute(Callable<Integer> command) {
        return new CommandLine(command)
                .setExecutionExceptionHandler(new ExecutionErrorHandler())
                .setErr(new PrintWriter(err, true))
                .execute();
    }

    @Command(name = "failing")
    private record Failing(Exception thrown) implements Callable<Integer> {
        @Override
        public Integer call() throws Exception {
            throw thrown;
        }
    }
}
