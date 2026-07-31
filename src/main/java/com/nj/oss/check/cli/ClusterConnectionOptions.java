package com.nj.oss.check.cli;

import com.nj.oss.check.collect.ClusterConnection;
import picocli.CommandLine.Option;

import java.net.URI;

/**
 * How to reach a cluster, shared by {@code collect} and {@code diagnose
 * --endpoint}.
 *
 * <p>Both commands run the same collector, so their authentication path must
 * not diverge (DESIGN.md 3.2). Declaring these options once is what guarantees
 * that — two copies would drift the first time one of them changed.
 */
public class ClusterConnectionOptions {

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

    /** Replaced in tests. Not worth a Spring bean: nothing else needs it. */
    PasswordSource passwordSource = new PasswordSource();

    /**
     * Resolves the password and builds the connection. Throws rather than
     * falling back to an anonymous request when {@code --user} was given but no
     * password can be had (DESIGN.md 3.1).
     */
    ClusterConnection toConnection() {
        String password = user == null ? null : passwordSource.forUser(user);
        return new ClusterConnection(endpoint, user, password, insecure);
    }
}
