package com.nj.oss.check.collect;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Unparsed dump contents: raw JSON per collect target plus the metadata file.
 * This is the boundary between the collect layer (live HTTP or tar.gz) and the
 * snapshot parser — both modes converge here.
 *
 * @param metadataJson contents of {@code metadata.json}
 * @param payloads     raw JSON response per target; a target may be absent
 *                     when the API returned an error at collection time
 *                     (e.g. allocation/explain with no unassigned shards)
 */
public record RawDump(String metadataJson, Map<CollectTarget, String> payloads) {

    public RawDump {
        Objects.requireNonNull(metadataJson, "metadataJson");
        payloads = Map.copyOf(payloads);
    }

    public Optional<String> payload(CollectTarget target) {
        return Optional.ofNullable(payloads.get(target));
    }
}