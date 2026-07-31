package com.nj.oss.check.collect;

import java.net.URI;
import java.util.Objects;

/**
 * How to reach the cluster being collected from. Mirrors the connection
 * options of {@code collect} and {@code diagnose --endpoint} (DESIGN.md 3.1);
 * nothing else is configurable, timeouts included.
 *
 * @param endpoint cluster base URL, e.g. {@code https://opensearch.internal:9200}.
 *                 Must be http or https and must not carry userinfo — credentials
 *                 belong in {@code username} / {@code password}, because the
 *                 endpoint is echoed in collection failure messages
 * @param username basic-auth user, null when connecting anonymously. Always
 *                 travels with a password: the two are given together or not
 *                 at all (DESIGN.md 3.1)
 * @param password basic-auth password, null only when {@code username} is too.
 *                 An empty string is a password — it was deliberately given,
 *                 while null means none was supplied at all
 * @param insecure accept a TLS certificate this host does not trust, typically
 *                 self-signed. Common enough inside closed networks to be worth
 *                 an explicit opt-in flag, and dangerous enough not to be the
 *                 default. It does <b>not</b> waive hostname verification: a
 *                 certificate issued for a name other than the endpoint's is
 *                 still rejected, so reaching a cluster by IP needs a
 *                 certificate that covers that IP
 */
public record ClusterConnection(
        URI endpoint,
        String username,
        String password,
        boolean insecure) {

    public ClusterConnection {
        Objects.requireNonNull(endpoint, "endpoint");
        // Checked before the scheme so that no message below can echo a URL
        // that still carries a password.
        if (endpoint.getUserInfo() != null) {
            throw new IllegalArgumentException("Endpoint must not carry credentials");
        }
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Endpoint must be http or https: " + endpoint);
        }
        // Half a credential means an anonymous request would go out under a
        // name the caller believes is authenticating. The 401/403 that comes
        // back would then be recorded as the account lacking permission,
        // hiding the real cause in a dump read months later (DESIGN.md 3.1).
        if ((username == null) != (password == null)) {
            throw new IllegalArgumentException("User and password must be given together");
        }
        if (username != null && username.isBlank()) {
            throw new IllegalArgumentException("User must not be blank");
        }
    }

    /** No credentials, TLS verified. */
    public static ClusterConnection of(URI endpoint) {
        return new ClusterConnection(endpoint, null, null, false);
    }

    /**
     * True when this connection authenticates. Testing the user alone is
     * enough: the constructor guarantees a password came with it.
     */
    public boolean hasCredentials() {
        return username != null;
    }
}
