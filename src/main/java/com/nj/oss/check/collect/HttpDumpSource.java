package com.nj.oss.check.collect;

import com.nj.oss.check.snapshot.SnapshotMetadata;
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
            if (fetched.isOk()) {
                payloads.put(target, fetched.body());
                outcomes.add(CollectionOutcome.ok(target, fetched.httpStatus()));
                continue;
            }
            outcomes.add(CollectionOutcome.failed(target, fetched.httpStatus(), fetched.message()));
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
                outcomes);
        return new RawDump(mapper.writeValueAsString(metadata), payloads);
    }

    /**
     * Cluster name and version come from the root endpoint, which is not a
     * collect target — it identifies the dump rather than describing cluster
     * state. Failure here is not fatal: an unnamed dump is still diagnosable,
     * and if the cluster is truly unreachable the REQUIRED targets will say so
     * with a better message.
     */
    private ClusterIdentity fetchIdentity() throws InterruptedIOException {
        Fetched fetched = fetch("");
        if (!fetched.isOk()) {
            return new ClusterIdentity(null, null);
        }
        try {
            JsonNode root = mapper.readTree(fetched.body());
            return new ClusterIdentity(
                    root.path("cluster_name").asString(null),
                    root.path("version").path("number").asString(null));
        } catch (RuntimeException e) {
            return new ClusterIdentity(null, null);
        }
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

    private record ClusterIdentity(String clusterName, String clusterVersion) {
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
