package com.nj.oss.check.cli;

import java.io.Console;
import java.util.function.UnaryOperator;

/**
 * Finds the password that goes with {@code --user} (DESIGN.md 3.1).
 *
 * <p>There is no {@code --password} option: a password on the command line
 * lands in shell history and is visible to every other user of the host through
 * {@code ps}. So it comes from the environment (the non-interactive path, for
 * cron and CI) or from a prompt, in that order.
 */
final class PasswordSource {

    static final String ENVIRONMENT_VARIABLE = "OSS_CHECK_PASSWORD";

    private final UnaryOperator<String> environment;
    private final Console console;

    PasswordSource() {
        this(System::getenv, System.console());
    }

    PasswordSource(UnaryOperator<String> environment, Console console) {
        this.environment = environment;
        this.console = console;
    }

    /**
     * Never returns null and never falls back to an anonymous request: going
     * out unauthenticated under a name the operator believes is authenticating
     * would have the cluster's 401/403 recorded as "this account has no
     * permission", hiding the real cause in a dump read months later.
     *
     * @throws IllegalStateException when no password can be obtained
     */
    String forUser(String user) {
        String fromEnvironment = environment.apply(ENVIRONMENT_VARIABLE);
        if (fromEnvironment != null) {
            return fromEnvironment;
        }
        // Not a terminal means a pipe, a redirect, or cron. Asking there would
        // hang a scheduled run forever, which DESIGN.md 3.2 forbids outright.
        if (console != null && console.isTerminal()) {
            return new String(console.readPassword("Password for %s: ", user));
        }
        throw new IllegalStateException("No password for user '" + user + "'. Set "
                + ENVIRONMENT_VARIABLE + ", or run in a terminal to be asked for it.");
    }
}
