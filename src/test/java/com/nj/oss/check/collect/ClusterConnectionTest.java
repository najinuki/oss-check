package com.nj.oss.check.collect;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterConnectionTest {

    private static final URI ENDPOINT = URI.create("https://opensearch.internal:9200");

    @Test
    void aUserWithoutAPasswordIsRejectedRatherThanSentAnonymously() {
        // the operator passed --user and the password never arrived: going out
        // anonymously would record the cluster's 403 as "this account has no
        // permission", hiding a config mistake in the dump (DESIGN.md 3.1)
        assertThatThrownBy(() -> new ClusterConnection(ENDPOINT, "admin", null, false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aPasswordWithoutAUserIsRejected() {
        assertThatThrownBy(() -> new ClusterConnection(ENDPOINT, null, "s3cr3t", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankUserIsRejected() {
        assertThatThrownBy(() -> new ClusterConnection(ENDPOINT, "  ", "s3cr3t", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEmptyPasswordIsStillAPassword() {
        // "" was deliberately given; only null means none was supplied
        assertThat(new ClusterConnection(ENDPOINT, "admin", "", false).hasCredentials()).isTrue();
    }

    @Test
    void noCredentialsAtAllIsAValidConnection() {
        assertThat(ClusterConnection.of(ENDPOINT).hasCredentials()).isFalse();
    }

    @Test
    void credentialsInTheEndpointAreRejectedWithoutEchoingThePassword() {
        // the endpoint goes into collection failure messages, so a URL with
        // userinfo would carry the password into the dump report
        assertThatThrownBy(() -> ClusterConnection.of(URI.create("https://admin:s3cr3t@host:9200")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageNotContaining("s3cr3t");
    }

    @Test
    void onlyHttpAndHttpsEndpointsAreAccepted() {
        assertThatThrownBy(() -> ClusterConnection.of(URI.create("ftp://host:9200")))
                .isInstanceOf(IllegalArgumentException.class);
        // a missing scheme parses as an opaque URI, not as a host
        assertThatThrownBy(() -> ClusterConnection.of(URI.create("host:9200")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
