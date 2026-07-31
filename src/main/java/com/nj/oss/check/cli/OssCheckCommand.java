package com.nj.oss.check.cli;

import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * Top-level command. Holds no options of its own — everything happens in
 * {@code collect} or {@code diagnose}, and there is no third subcommand
 * (DESIGN.md 3).
 */
@Component
@Command(
        name = "oss-check",
        description = "Diagnose an OpenSearch cluster from its own API responses.",
        mixinStandardHelpOptions = true,
        versionProvider = OssCheckCommand.Version.class,
        subcommands = {CollectCommand.class, DiagnoseCommand.class})
public class OssCheckCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    /**
     * The command tree with this tool's exit-code contract already applied.
     * Built here rather than at the entry point so that what tests exercise is
     * what ships.
     */
    public static CommandLine commandLine(CommandLine.IFactory factory) {
        return new CommandLine(OssCheckCommand.class, factory)
                .setExecutionExceptionHandler(new ExecutionErrorHandler())
                // so that --format json works, not only --format JSON
                .setCaseInsensitiveEnumValuesAllowed(true);
    }

    @Override
    public Integer call() {
        // Invoked without a subcommand: show what the tool can do and exit as
        // a usage error, the same as any other unusable command line.
        spec.commandLine().usage(spec.commandLine().getErr());
        return ExitCode.ERROR;
    }

    static class Version implements picocli.CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"oss-check " + ToolVersion.VERSION};
        }
    }
}
