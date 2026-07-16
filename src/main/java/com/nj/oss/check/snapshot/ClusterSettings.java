package com.nj.oss.check.snapshot;

import java.util.Map;
import java.util.Optional;

/**
 * Parsed {@code _cluster/settings?include_defaults=true} response.
 *
 * <p>Each scope is flattened to dotted keys at parse time
 * (e.g. {@code "cluster.routing.allocation.enable" -> "none"}), so rules look
 * settings up by the same key they would pass to the settings API. Array
 * values are flattened to a comma-joined string.
 *
 * @param persistentSettings the {@code persistent} scope
 * @param transientSettings  the {@code transient} scope
 * @param defaultSettings    the {@code defaults} scope
 */
public record ClusterSettings(
        Map<String, String> persistentSettings,
        Map<String, String> transientSettings,
        Map<String, String> defaultSettings) {

    public ClusterSettings {
        persistentSettings = Map.copyOf(persistentSettings);
        transientSettings = Map.copyOf(transientSettings);
        defaultSettings = Map.copyOf(defaultSettings);
    }

    /**
     * Effective value the cluster is running with, applying OpenSearch
     * precedence: transient over persistent over defaults.
     */
    public Optional<String> effective(String dottedKey) {
        return explicit(dottedKey)
                .or(() -> Optional.ofNullable(defaultSettings.get(dottedKey)));
    }

    /**
     * Value only if an operator explicitly set it (transient or persistent),
     * ignoring defaults. Useful for detecting misconfiguration as opposed to
     * default behavior.
     */
    public Optional<String> explicit(String dottedKey) {
        return Optional.ofNullable(transientSettings.get(dottedKey))
                .or(() -> Optional.ofNullable(persistentSettings.get(dottedKey)));
    }
}