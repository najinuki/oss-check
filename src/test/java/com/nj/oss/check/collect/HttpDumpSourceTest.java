package com.nj.oss.check.collect;

import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.parse.ClusterSnapshotParser;
import com.nj.oss.check.testsupport.Fixtures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpDumpSourceTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-07-28T09:00:00Z");

    /** Long enough for a loaded CI machine, short enough to fail rather than hang. */
    private static final long AWAIT_SECONDS = 10;

    private HttpServer server;
    private URI endpoint;

    /** Path the handler stalls on, so a request can be caught in flight. */
    private volatile String stalledPath;
    private final CountDownLatch stalledRequestArrived = new CountDownLatch(1);
    private final CountDownLatch releaseStalledRequest = new CountDownLatch(1);

    /** Response body per request path (path only, query stripped). */
    private final Map<String, String> bodies = new HashMap<>();
    /** Status code per request path; absent means 200. */
    private final Map<String, Integer> statuses = new HashMap<>();
    /** Paths answered with a 302 to the mapped location. */
    private final Map<String, String> redirects = new HashMap<>();
    /**
     * What was actually asked for, query string included. The query is where
     * {@code bytes=b} and {@code include_defaults=true} live, and those are
     * parsing contracts — dropping them here would let the test pass while the
     * collector asked for something the parser cannot read.
     */
    private final List<String> requestedUris = new ArrayList<>();
    private final List<String> authorizationHeaders = new ArrayList<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort());

        serve("/", """
                { "cluster_name": "prod-search", "version": { "number": "2.19.1" } }
                """);
        // Real response shapes for the targets the parser reads; the eight
        // collect-only targets have no fixture yet and are not parsed.
        RawDump fixtures = Fixtures.load("normal");
        for (CollectTarget target : CollectTarget.values()) {
            serve("/" + pathOf(target), fixtures.payload(target).orElse("{}"));
        }
    }

    @AfterEach
    void stopServer() {
        releaseStalledRequest.countDown();
        server.stop(0);
    }

    @Test
    void collectsEveryTargetAndRecordsThemAsOk() throws IOException {
        RawDump dump = source().load();

        assertThat(dump.payloads()).hasSize(CollectTarget.values().length);
        assertThat(requestedUris).contains("/", "/_cluster/health", "/_nodes/stats", "/_index_template");

        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(dump);
        assertThat(snapshot.metadata().clusterName()).isEqualTo("prod-search");
        assertThat(snapshot.metadata().clusterVersion()).isEqualTo("2.19.1");
        assertThat(snapshot.metadata().collectedAt()).isEqualTo(COLLECTED_AT);
        assertThat(snapshot.metadata().toolVersion()).isEqualTo("0.1.0-TEST");
        assertThat(snapshot.metadata().collection())
                .hasSize(CollectTarget.values().length)
                .allMatch(CollectionOutcome::isOk);
    }

    @Test
    void requestsEachTargetAtItsDeclaredPathWithItsQueryIntact() throws IOException {
        source().load();

        // the enum is the single source of truth for what gets called, down to
        // the query string: bytes=b is what makes sizes arrive as numbers, and
        // include_defaults=true is what makes settings diagnosable at all
        for (CollectTarget target : CollectTarget.values()) {
            assertThat(requestedUris).contains("/" + target.path());
        }
    }

    @Test
    void optionalFailureIsRecordedAndCollectionContinues() throws IOException {
        // a security plugin denying index-level stats is an ordinary situation
        fail("/_cat/indices", 403, """
                { "error": { "reason": "no permissions for [indices:monitor/stats]" } }
                """);

        RawDump dump = source().load();

        assertThat(dump.payload(CollectTarget.CAT_INDICES)).isEmpty();
        assertThat(dump.payloads()).hasSize(CollectTarget.values().length - 1);

        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(dump);
        assertThat(snapshot.indices()).isEmpty();
        CollectionOutcome outcome = snapshot.metadata().outcomeOf(CollectTarget.CAT_INDICES).orElseThrow();
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(403);
        assertThat(outcome.describeFailure())
                .hasValueSatisfying(failure -> assertThat(failure)
                        .contains("HTTP 403", "no permissions for [indices:monitor/stats]"));

        // and the rule that needs it can say exactly why it was skipped
        assertThat(snapshot.absenceReason(CollectTarget.CAT_INDICES))
                .contains("cat_indices.json", "HTTP 403", "no permissions");
    }

    @Test
    void allocationExplainErrorOnAHealthyClusterIsNotFatal() throws IOException {
        // this API answers 400 when there is no unassigned shard to explain
        fail("/_cluster/allocation/explain", 400, """
                { "error": { "reason": "unable to find any unassigned shards to explain" } }
                """);

        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(source().load());

        assertThat(snapshot.allocationExplain()).isEmpty();
        assertThat(snapshot.metadata().outcomeOf(CollectTarget.ALLOCATION_EXPLAIN).orElseThrow().httpStatus())
                .isEqualTo(400);
    }

    @Test
    void requiredFailureAbortsCollection() {
        fail("/_nodes/stats", 500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> source().load())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("nodes_stats.json")
                .hasMessageContaining("HTTP 500");
    }

    @Test
    void unreachableClusterFailsWithTheEndpointInTheMessage() {
        server.stop(0);
        HttpDumpSource dead = new HttpDumpSource(
                ClusterConnection.of(endpoint), "0.1.0-TEST", fixedClock());

        assertThatThrownBy(dead::load)
                .isInstanceOf(IOException.class)
                .hasMessageContaining(endpoint.toString());
    }

    @Test
    void sendsBasicAuthWhenCredentialsAreGiven() throws IOException {
        new HttpDumpSource(
                new ClusterConnection(endpoint, "admin", "s3cr3t", false),
                "0.1.0-TEST", fixedClock()).load();

        // "admin:s3cr3t" base64-encoded
        assertThat(authorizationHeaders).isNotEmpty().allMatch("Basic YWRtaW46czNjcjN0"::equals);
    }

    @Test
    void sendsNoAuthorizationHeaderWithoutCredentials() throws IOException {
        source().load();

        assertThat(authorizationHeaders).isEmpty();
    }

    @Test
    void unidentifiableClusterStillProducesADiagnosableDump() throws IOException {
        // the root endpoint can be blocked while the rest is readable
        fail("/", 403, "denied");

        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(source().load());

        assertThat(snapshot.metadata().clusterName()).isNull();
        assertThat(snapshot.metadata().clusterVersion()).isNull();
        assertThat(snapshot.health()).isNotNull();
    }

    @Test
    void redirectsAreNotFollowedSoCredentialsCannotLeaveTheEndpoint() throws IOException {
        // every request carries the Basic header, and a Location can point at
        // any host — so a redirect is recorded as a failure, not followed
        redirectTo("/_cat/indices", "http://127.0.0.1:1/_cat/indices");

        RawDump dump = new HttpDumpSource(
                new ClusterConnection(endpoint, "admin", "s3cr3t", false),
                "0.1.0-TEST", fixedClock()).load();

        assertThat(dump.payload(CollectTarget.CAT_INDICES)).isEmpty();
        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(dump);
        CollectionOutcome outcome = snapshot.metadata().outcomeOf(CollectTarget.CAT_INDICES).orElseThrow();
        assertThat(outcome.isOk()).isFalse();
        assertThat(outcome.httpStatus()).isEqualTo(302);
    }

    @Test
    void interruptAbortsCollectionInsteadOfProducingAPartialDump() throws InterruptedException {
        // an OPTIONAL target: recording the interrupt as an ordinary target
        // failure would let load() return a dump that looks merely partial,
        // turning a cancelled run into a diagnosable-looking result
        stalledPath = "/_cat/indices";
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        Thread collector = new Thread(() -> {
            try {
                source().load();
            } catch (Throwable t) {
                failure.set(t);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        });

        collector.start();
        assertThat(stalledRequestArrived.await(AWAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
        collector.interrupt();
        collector.join(TimeUnit.SECONDS.toMillis(AWAIT_SECONDS));

        assertThat(collector.isAlive()).isFalse();
        assertThat(failure.get())
                .isInstanceOf(InterruptedIOException.class)
                .hasMessageContaining(endpoint.toString())
                .hasMessageContaining("_cat/indices");
        // the caller who cancelled has to be able to see it was cancelled
        assertThat(interruptFlagRestored).isTrue();
        // and collection stopped there rather than walking the remaining targets
        assertThat(requestedUris).doesNotContain("/_index_template");
    }

    @Test
    void recordedErrorBodyIsTruncated() throws IOException {
        fail("/_cat/shards", 500, "x".repeat(HttpDumpSource.MAX_RECORDED_ERROR_LENGTH * 3));

        RawDump dump = source().load();
        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(dump);

        String message = snapshot.metadata().outcomeOf(CollectTarget.CAT_SHARDS).orElseThrow().message();
        assertThat(message).hasSize(HttpDumpSource.MAX_RECORDED_ERROR_LENGTH + 1).endsWith("…");
    }

    private HttpDumpSource source() {
        return new HttpDumpSource(ClusterConnection.of(endpoint), "0.1.0-TEST", fixedClock());
    }

    private static Clock fixedClock() {
        return Clock.fixed(COLLECTED_AT, ZoneOffset.UTC);
    }

    /** Request path of a target, without its query string. */
    private static String pathOf(CollectTarget target) {
        String path = target.path();
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
    }

    /** Bounded so a released-too-late latch cannot hang the suite. */
    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void serve(String path, String body) {
        bodies.put(path, body);
    }

    private void fail(String path, int status, String body) {
        bodies.put(path, body);
        statuses.put(path, status);
    }

    private void redirectTo(String path, String location) {
        redirects.put(path, location);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery();
        requestedUris.add(query == null ? path : path + "?" + query);
        if (path.equals(stalledPath)) {
            stalledRequestArrived.countDown();
            awaitQuietly(releaseStalledRequest);
        }
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) {
            authorizationHeaders.add(authorization);
        }
        try (InputStream body = exchange.getRequestBody()) {
            body.readAllBytes();
        }

        String location = redirects.get(path);
        if (location != null) {
            exchange.getResponseHeaders().set("Location", location);
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
            return;
        }

        byte[] response = bodies.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
