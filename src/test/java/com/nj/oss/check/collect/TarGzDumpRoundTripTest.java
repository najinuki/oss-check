package com.nj.oss.check.collect;

import com.nj.oss.check.snapshot.ClusterSnapshot;
import com.nj.oss.check.snapshot.HealthStatus;
import com.nj.oss.check.snapshot.parse.ClusterSnapshotParser;
import com.nj.oss.check.testsupport.Fixtures;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TarGzDumpRoundTripTest {

    private final TarGzDumpWriter writer = new TarGzDumpWriter();

    @TempDir
    Path tempDir;

    @Test
    void writtenDumpReadsBackIdentically() throws IOException {
        RawDump original = Fixtures.load("normal");
        Path archive = tempDir.resolve("dump.tar.gz");

        writer.write(original, archive);
        RawDump reloaded = new TarGzDumpSource(archive).load();

        assertThat(reloaded.metadataJson()).isEqualTo(original.metadataJson());
        assertThat(reloaded.payloads()).isEqualTo(original.payloads());
    }

    @Test
    void partialDumpKeepsItsGaps() throws IOException {
        // a dump where OPTIONAL targets failed to collect must not gain them back
        RawDump partial = Fixtures.load("required-only");
        Path archive = tempDir.resolve("partial.tar.gz");

        writer.write(partial, archive);
        RawDump reloaded = new TarGzDumpSource(archive).load();

        assertThat(reloaded.payloads()).containsOnlyKeys(CollectTarget.CLUSTER_HEALTH, CollectTarget.NODES_STATS);
        assertThat(reloaded.payload(CollectTarget.CLUSTER_SETTINGS)).isEmpty();
    }

    @Test
    void archiveParsesIntoAUsableSnapshot() throws IOException {
        // the point of the archive: an offline dump must reach the rules with
        // the same content a live collection would have produced
        Path archive = tempDir.resolve("dump.tar.gz");
        writer.write(Fixtures.load("normal"), archive);

        ClusterSnapshot snapshot = new ClusterSnapshotParser().parse(new TarGzDumpSource(archive).load());

        assertThat(snapshot.health().status()).isEqualTo(HealthStatus.GREEN);
        assertThat(snapshot.nodesStats().dataNodeCount()).isEqualTo(3);
        assertThat(snapshot.settings().orElseThrow().effective("cluster.max_shards_per_node")).contains("1000");
        assertThat(snapshot.shards().orElseThrow()).hasSize(10);
        assertThat(snapshot.metadata().clusterName()).isEqualTo("fixture-cluster");
    }

    @Test
    void createsMissingParentDirectories() throws IOException {
        Path archive = tempDir.resolve("nested/deeper/dump.tar.gz");

        writer.write(Fixtures.load("normal"), archive);

        assertThat(archive).exists();
    }

    @Test
    void refusesToReplaceAnExistingDump() throws IOException {
        // a dump is evidence: a second run must not destroy the state the
        // first one was collected to capture. CREATE_NEW makes the check and
        // the creation one step, so a concurrent run cannot slip between them.
        Path archive = Files.writeString(tempDir.resolve("dump.tar.gz"), "an earlier collection");

        assertThatThrownBy(() -> writer.write(Fixtures.load("normal"), archive))
                .isInstanceOf(FileAlreadyExistsException.class);
        assertThat(archive).hasContent("an earlier collection");
    }

    @Test
    void entriesAreNamedByCollectTarget() throws IOException {
        // the writer must not invent names: the reader matches on these exact
        // file names, and an operator opening the archive with `tar -xzf`
        // needs to know what each response is
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writer.write(Fixtures.load("normal"), out);

        assertThat(entryNames(out.toByteArray()))
                .containsExactly(
                        CollectTarget.METADATA_FILE_NAME,
                        CollectTarget.CLUSTER_HEALTH.fileName(),
                        CollectTarget.NODES_STATS.fileName(),
                        CollectTarget.CLUSTER_SETTINGS.fileName(),
                        CollectTarget.ALLOCATION_EXPLAIN.fileName(),
                        CollectTarget.CAT_SHARDS.fileName(),
                        CollectTarget.CAT_INDICES.fileName(),
                        CollectTarget.CAT_ALLOCATION.fileName());
    }

    @Test
    void ignoresEntriesThatMatchNoTarget() throws IOException {
        // a dump from a newer tool, plus operator clutter, must still read
        Path archive = tempDir.resolve("with-extras.tar.gz");
        writeArchive(archive, Map.of(
                CollectTarget.METADATA_FILE_NAME, "{}",
                "cluster_health.json", "{\"status\":\"green\"}",
                "hot_threads.txt", "not json at all",
                "README", "hand written note"));

        RawDump dump = new TarGzDumpSource(archive).load();

        assertThat(dump.payloads()).containsOnlyKeys(CollectTarget.CLUSTER_HEALTH);
        assertThat(dump.metadataJson()).isEqualTo("{}");
    }

    @Test
    void readsEntriesNestedUnderADirectory() throws IOException {
        // `tar -czf dump.tar.gz mydump/` produces this shape
        Path archive = tempDir.resolve("nested.tar.gz");
        writeArchive(archive, Map.of(
                "mydump/" + CollectTarget.METADATA_FILE_NAME, "{}",
                "mydump/cluster_health.json", "{\"status\":\"red\"}"));

        RawDump dump = new TarGzDumpSource(archive).load();

        assertThat(dump.payload(CollectTarget.CLUSTER_HEALTH)).contains("{\"status\":\"red\"}");
    }

    @Test
    void failsWhenMetadataIsMissing() throws IOException {
        Path archive = tempDir.resolve("no-metadata.tar.gz");
        writeArchive(archive, Map.of("cluster_health.json", "{}"));

        assertThatThrownBy(() -> new TarGzDumpSource(archive).load())
                .isInstanceOf(IOException.class)
                .hasMessageContaining(CollectTarget.METADATA_FILE_NAME);
    }

    @Test
    void failsWhenTheSameFileAppearsTwice() throws IOException {
        // no basis for picking one, so it must not pick silently
        Path archive = tempDir.resolve("duplicate.tar.gz");
        writeArchive(archive, List.of(
                entry(CollectTarget.METADATA_FILE_NAME, "{}"),
                entry("a/cluster_health.json", "{\"status\":\"green\"}"),
                entry("b/cluster_health.json", "{\"status\":\"red\"}")));

        assertThatThrownBy(() -> new TarGzDumpSource(archive).load())
                .isInstanceOf(IOException.class)
                .hasMessageContaining("more than once");
    }

    private static List<String> entryNames(byte[] archive) throws IOException {
        List<String> names = new ArrayList<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new GZIPInputStream(new ByteArrayInputStream(archive)))) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static Map.Entry<String, String> entry(String name, String content) {
        return Map.entry(name, content);
    }

    private static void writeArchive(Path target, Map<String, String> entries) throws IOException {
        writeArchive(target, List.copyOf(entries.entrySet()));
    }

    private static void writeArchive(Path target, List<Map.Entry<String, String>> entries) throws IOException {
        try (TarArchiveOutputStream tar = new TarArchiveOutputStream(
                new GZIPOutputStream(Files.newOutputStream(target)))) {
            for (Map.Entry<String, String> each : entries) {
                byte[] bytes = each.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry archiveEntry = new TarArchiveEntry(each.getKey());
                archiveEntry.setSize(bytes.length);
                tar.putArchiveEntry(archiveEntry);
                tar.write(bytes);
                tar.closeArchiveEntry();
            }
            tar.finish();
        }
    }
}
