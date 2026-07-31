package com.nj.oss.check.cli;

import com.nj.oss.check.rule.DiagnosticReport;
import com.nj.oss.check.snapshot.SnapshotMetadata;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReportRendererTest {

    private static final Instant COLLECTED_AT = Instant.parse("2026-07-31T04:11:00Z");
    private static final DiagnosticReport NOTHING = new DiagnosticReport(List.of(), List.of());

    @Test
    void headsTheReportWithTheClusterItIsAbout() {
        assertThat(text(metadata("prod-search", "2.19.1", null)))
                .startsWith("prod-search (OpenSearch 2.19.1) - collected 2026-07-31T04:11:00Z");
    }

    @Test
    void saysWhyTheClusterHasNoName() {
        // "unknown cluster" alone blurs a cluster that answered oddly into one
        // we were never allowed to ask
        String header = text(metadata(null, null,
                "root endpoint returned HTTP 200 but the body was not JSON: <!DOCTYPE html>"));

        assertThat(header).startsWith("unknown cluster (root endpoint returned HTTP 200");
    }

    @Test
    void theJsonReportCarriesTheSameReason() {
        JsonNode report = JsonMapper.builder().build().readTree(
                ReportRenderer.json(metadata(null, null, "root endpoint HTTP 403: denied"), NOTHING));

        assertThat(report.path("cluster").path("identityFailure").asString())
                .isEqualTo("root endpoint HTTP 403: denied");
    }

    private static String text(SnapshotMetadata metadata) {
        StringWriter out = new StringWriter();
        ReportRenderer.text(new PrintWriter(out, true), metadata, NOTHING);
        return out.toString();
    }

    private static SnapshotMetadata metadata(String name, String version, String identityFailure) {
        return new SnapshotMetadata(
                SnapshotMetadata.CURRENT_DUMP_SCHEMA_VERSION,
                COLLECTED_AT,
                "0.1.0-TEST",
                name,
                version,
                identityFailure,
                List.of());
    }
}
