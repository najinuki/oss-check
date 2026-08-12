package com.nj.oss.check.collect;

import com.nj.oss.check.snapshot.SnapshotMetadata;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Collects every {@link CollectTarget} from a live cluster over HTTP — the
 * live half of {@code collect} and {@code diagnose --endpoint}.
 *
 * <p>A partial collection is a normal outcome, not a failure: an OPTIONAL
 * target that returns 403, times out, or does not exist on this cluster
 * version is recorded in the collection report and collection continues. Only
 * a REQUIRED target failing aborts, because without it there is no diagnosable
 * dump at all.
 */
public final class HttpDumpSource implements DumpSource {

    /**
     * Enough for a busy cluster to accept a connection, short enough that a
     * wrong endpoint fails while the operator is still watching.
     */
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Generous because {@code _nodes/stats} on a large cluster is a big
     * response the master may take a while to assemble.
     */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Error bodies go into the dump's collection report, which humans read.
     * OpenSearch error bodies can carry a full stack trace, so they are cut.
     */
    static final int MAX_RECORDED_ERROR_LENGTH = 500;

    private final ClusterConnection connection;
    private final String toolVersion;
    private final Clock clock;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            // Absent means absent. A null written out is noise in a file that
            // has to stay readable by hand years from now, and the identity
            // failure field in particular is defined by its presence.
            .changeDefaultPropertyInclusion(inclusion -> inclusion.withValueInclusion(Include.NON_NULL))
            .addMixIn(SnapshotMetadata.class, NoDerivedState.class)
            .addMixIn(CollectionOutcome.class, NoDerivedState.class)
            .build();

    public HttpDumpSource(ClusterConnection connection, String toolVersion) {
        this(connection, toolVersion, Clock.systemUTC());
    }

    HttpDumpSource(ClusterConnection connection, String toolVersion, Clock clock) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.toolVersion = Objects.requireNonNull(toolVersion, "toolVersion");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.client = buildClient(connection);
    }

    @Override
    public RawDump load() throws IOException {
        ClusterIdentity identity = fetchIdentity();
        Map<CollectTarget, String> payloads = new EnumMap<>(CollectTarget.class);
        List<CollectionOutcome> outcomes = new ArrayList<>();
        List<String> requiredFailures = new ArrayList<>();

        for (CollectTarget target : CollectTarget.values()) {
            Fetched fetched = fetch(target.path());
            // Stamped per response, not once per sweep: this is the instant the
            // counters in this payload were read, and rate rules divide by the
            // gap between these.
            Instant receivedAt = clock.instant();
            if (fetched.isOk()) {
                payloads.put(target, fetched.body());
                outcomes.add(CollectionOutcome.ok(target, fetched.httpStatus(), receivedAt));
                continue;
            }
            outcomes.add(CollectionOutcome.failed(target, fetched.httpStatus(), fetched.message(), receivedAt));
            if (target.isRequired()) {
                requiredFailures.add(target.fileName() + " (" + fetched.describe() + ")");
            }
        }

        if (!requiredFailures.isEmpty()) {
            throw new IOException("Could not collect required data from " + connection.endpoint()
                    + ": " + String.join(", ", requiredFailures));
        }

        SnapshotMetadata metadata = new SnapshotMetadata(
                SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION,
                clock.instant(),
                toolVersion,
                identity.clusterName(),
                identity.clusterVersion(),
                identity.failure(),
                outcomes);
        return new RawDump(mapper.writeValueAsString(metadata), payloads);
    }

    /**
     * Cluster name and version come from the root endpoint, which is not a
     * collect target — it identifies the dump rather than describing cluster
     * state. Failure here is not fatal: an unnamed dump is still diagnosable,
     * and if the cluster is truly unreachable the REQUIRED targets will say so
     * with a better message.
     *
     * <p>It is recorded, though. Without a reason, a nameless dump looks the
     * same whether the endpoint was denied, fronted by a proxy that answered
     * with its login page, or something else entirely (DESIGN.md 3.1).
     */
    private ClusterIdentity fetchIdentity() throws InterruptedIOException {
        Fetched fetched = fetch("");
        if (!fetched.isOk()) {
            return ClusterIdentity.unidentified("root endpoint " + fetched.describe());
        }
        JsonNode root;
        try {
            root = mapper.readTree(fetched.body());
        } catch (RuntimeException e) {
            // A 2xx whose body is not JSON is the signature of something
            // answering on the cluster's behalf, so the body says more than the
            // exception does.
            return ClusterIdentity.unidentified(
                    "root endpoint returned HTTP " + fetched.httpStatus()
                            + " but the body was not JSON: " + truncate(fetched.body()));
        }
        String name = root.path("cluster_name").asString(null);
        String version = root.path("version").path("number").asString(null);
        if (name == null && version == null) {
            return ClusterIdentity.unidentified(
                    "root endpoint returned JSON without cluster_name or version");
        }
        return new ClusterIdentity(name, version, null);
    }

    /**
     * A cancelled collection is not a partial one: an interrupt aborts
     * {@link #load()} instead of being recorded as a target failure, because a
     * dump that silently omits whatever was left when the operator hit Ctrl-C
     * would be diagnosed as if the cluster had answered.
     */
    private Fetched fetch(String path) throws InterruptedIOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uriFor(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .GET();
        if (connection.hasCredentials()) {
            request.header("Authorization", basicAuth());
        }
        try {
            HttpResponse<String> response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 == 2) {
                return Fetched.ok(response.statusCode(), response.body());
            }
            return Fetched.failed(response.statusCode(), truncate(response.body()));
        } catch (IOException e) {
            return Fetched.failed(null, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                    "Collection from " + connection.endpoint() + " was interrupted while requesting "
                            + (path.isEmpty() ? "/" : path));
            interrupted.initCause(e);
            throw interrupted;
        }
    }

    private URI uriFor(String path) {
        String base = connection.endpoint().toString();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(path.isEmpty() ? base + "/" : base + "/" + path);
    }

    private String basicAuth() {
        String credentials = connection.username() + ":" + connection.password();
        return "Basic " + Base64.getEncoder()
                .encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String truncate(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String collapsed = body.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= MAX_RECORDED_ERROR_LENGTH
                ? collapsed
                : collapsed.substring(0, MAX_RECORDED_ERROR_LENGTH) + "…";
    }

    private static HttpClient buildClient(ClusterConnection connection) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // Every request carries the Basic credentials, so following a
                // redirect could hand them to whatever host the Location points
                // at. OpenSearch APIs do not redirect: there is nothing to gain.
                .followRedirects(HttpClient.Redirect.NEVER);
        if (connection.insecure()) {
            builder.sslContext(trustAllContext());
        }
        return builder.build();
    }

    /**
     * Accepts any certificate chain — the whole of what {@code --insecure}
     * promises. Hostname verification is <b>not</b> disabled here: the JDK
     * client re-enables endpoint identification for HTTPS whatever
     * {@code SSLParameters} it is handed, so an endpoint must still match the
     * name its certificate was issued for.
     */
    private static SSLContext trustAllContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustAllCertificates()}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not build an insecure TLS context", e);
        }
    }

    /** Only ever installed behind the explicit {@code --insecure} flag. */
    private static final class TrustAllCertificates implements X509TrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    /**
     * Keeps convenience accessors out of the archive. {@code isOk()},
     * {@code isIdentified()} and {@code isNewerThanSupported()} read as bean
     * getters to Jackson, so without this they are written to the file
     * alongside the values they are derived from.
     *
     * <p>Two of them would be actively misleading there. {@code ok} restates
     * {@code status}, giving a file that is read months later two answers to
     * the same question. {@code newer_than_supported} is a judgment the
     * <em>reader</em> makes by comparing the dump against its own version — at
     * write time it is always false, so storing it means storing a fact that
     * was never true of anything but the writer.
     *
     * <p>The mixin lives here rather than as annotations on the records so that
     * the model stays free of wire-format concerns.
     */
    private abstract static class NoDerivedState {

        @JsonIgnore
        abstract boolean isOk();

        @JsonIgnore
        abstract boolean isIdentified();

        @JsonIgnore
        abstract boolean isNewerThanSupported();
    }

    private record ClusterIdentity(String clusterName, String clusterVersion, String failure) {

        static ClusterIdentity unidentified(String failure) {
            return new ClusterIdentity(null, null, failure);
        }
    }

    /** One HTTP attempt: either a body, or why there is none. */
    private record Fetched(Integer httpStatus, String body, String message) {

        static Fetched ok(int httpStatus, String body) {
            return new Fetched(httpStatus, body, null);
        }

        static Fetched failed(Integer httpStatus, String message) {
            return new Fetched(httpStatus, null, message);
        }

        boolean isOk() {
            return body != null;
        }

        String describe() {
            if (httpStatus == null) {
                return message == null ? "no response" : message;
            }
            return message == null ? "HTTP " + httpStatus : "HTTP " + httpStatus + ": " + message;
        }
    }
}
