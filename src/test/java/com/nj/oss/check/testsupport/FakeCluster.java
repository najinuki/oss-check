package com.nj.oss.check.testsupport;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.RawDump;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * A local HTTP server answering the collect targets with fixture responses.
 *
 * <p>Real requests over a real socket, because the thing worth testing about
 * the CLI's live path is that it talks to something — a stubbed collector would
 * verify the parts that were never in doubt.
 */
public final class FakeCluster implements AutoCloseable {

    private final HttpServer server;
    private final Map<String, String> bodies = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();

    private FakeCluster(String fixture) {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();

        bodies.put("/", """
                { "cluster_name": "fixture-cluster", "version": { "number": "2.19.1" } }
                """);
        RawDump dump = Fixtures.load(fixture);
        for (CollectTarget target : CollectTarget.values()) {
            bodies.put(pathOf(target), dump.payload(target).orElse("{}"));
        }
    }

    /** Serves the responses of the named fixture directory. */
    public static FakeCluster serving(String fixture) {
        return new FakeCluster(fixture);
    }

    /** Answers one target with something other than its fixture response. */
    public FakeCluster answering(CollectTarget target, String json) {
        bodies.put(pathOf(target), json);
        return this;
    }

    /** Makes one target fail, as a security plugin denying it would. */
    public FakeCluster failing(CollectTarget target, int status, String body) {
        bodies.put(pathOf(target), body);
        statuses.put(pathOf(target), status);
        return this;
    }

    public String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** The server records only the path, so the query string is dropped here too. */
    private static String pathOf(CollectTarget target) {
        String path = target.path();
        int query = path.indexOf('?');
        return "/" + (query < 0 ? path : path.substring(0, query));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try (InputStream body = exchange.getRequestBody()) {
            body.readAllBytes();
        }
        byte[] response = bodies.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
