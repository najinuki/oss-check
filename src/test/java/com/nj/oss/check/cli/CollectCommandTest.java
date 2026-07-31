package com.nj.oss.check.cli;

import com.nj.oss.check.collect.CollectTarget;
import com.nj.oss.check.collect.RawDump;
import com.nj.oss.check.collect.TarGzDumpSource;
import com.nj.oss.check.testsupport.Fixtures;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CollectCommandTest {

    @TempDir
    Path workDir;

    private final StringWriter out = new StringWriter();
    private final StringWriter err = new StringWriter();

    private HttpServer server;
    private String endpoint;
    private final Map<String, String> bodies = new HashMap<>();
    private final Map<String, Integer> statuses = new HashMap<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        endpoint = "http://127.0.0.1:" + server.getAddress().getPort();

        bodies.put("/", """
                { "cluster_name": "prod-search", "version": { "number": "2.19.1" } }
                """);
        RawDump fixtures = Fixtures.load("normal");
        for (CollectTarget target : CollectTarget.values()) {
            bodies.put("/" + pathOf(target), fixtures.payload(target).orElse("{}"));
        }
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void writesADumpThatReadsBackAsADump() throws IOException {
        Path dump = workDir.resolve("dump.tar.gz");

        int exitCode = execute("--endpoint", endpoint, "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(dump).exists();
        assertThat(out.toString()).contains("Wrote", dump.toString(), "15 of 15");
        // the file is only worth writing if the offline reader accepts it
        assertThat(new TarGzDumpSource(dump).load().payloads())
                .hasSize(CollectTarget.values().length);
    }

    @Test
    void aPartialCollectionSaysWhatIsMissing() throws IOException {
        statuses.put("/_cat/indices", 403);
        Path dump = workDir.resolve("dump.tar.gz");

        int exitCode = execute("--endpoint", endpoint, "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.NO_FINDINGS);
        assertThat(out.toString()).contains("14 of 15");
        assertThat(err.toString()).contains("cat_indices.json", "metadata.json");
    }

    @Test
    void anExistingDumpIsNotOverwritten() throws IOException {
        Path dump = Files.writeString(workDir.resolve("dump.tar.gz"), "an earlier collection");

        int exitCode = execute("--endpoint", endpoint, "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains("already exists");
        assertThat(dump).hasContent("an earlier collection");
    }

    @Test
    void aMissingPasswordStopsBeforeAnythingIsCollected() {
        Path dump = workDir.resolve("dump.tar.gz");
        CollectCommand command = new CollectCommand();
        command.passwordSource = new PasswordSource(name -> null, null);

        int exitCode = execute(command,
                "--endpoint", endpoint, "--user", "admin", "--output", dump.toString());

        assertThat(exitCode).isEqualTo(ExitCode.ERROR);
        assertThat(err.toString()).contains(PasswordSource.ENVIRONMENT_VARIABLE);
        assertThat(dump).doesNotExist();
    }

    @Test
    void theDefaultDumpNameCarriesTheCollectionTime() {
        Path name = CollectCommand.defaultOutput(Instant.parse("2026-07-31T04:11:00Z"));

        assertThat(name).hasFileName("oss-check-20260731T041100Z.tar.gz");
    }

    private int execute(String... args) {
        return execute(new CollectCommand(), args);
    }

    private int execute(CollectCommand command, String... args) {
        return new CommandLine(command)
                .setExecutionExceptionHandler(new ExecutionErrorHandler())
                .setOut(new PrintWriter(out, true))
                .setErr(new PrintWriter(err, true))
                .execute(args);
    }

    private static String pathOf(CollectTarget target) {
        String path = target.path();
        int query = path.indexOf('?');
        return query < 0 ? path : path.substring(0, query);
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
