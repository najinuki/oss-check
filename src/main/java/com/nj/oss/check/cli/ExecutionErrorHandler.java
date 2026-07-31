package com.nj.oss.check.cli;

import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

/**
 * Turns any exception escaping a command into {@link ExitCode#ERROR}.
 *
 * <p>picocli's default is exit code 1, which in this tool means <b>findings
 * were reported</b> (DESIGN.md 3.2). Left alone, a cluster that could not be
 * reached would be read by a script as a diagnosis rather than a failure to
 * run — the single worst confusion this tool could cause.
 *
 * <p>Operators get the message, not a stack trace.
 */
public final class ExecutionErrorHandler implements CommandLine.IExecutionExceptionHandler {

    @Override
    public int handleExecutionException(Exception e, CommandLine commandLine, ParseResult parseResult) {
        commandLine.getErr().println(describe(e));
        return ExitCode.ERROR;
    }

    /** Exceptions carrying no message (NPE and friends) still have to say something. */
    public static String describe(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.toString() : message;
    }
}
