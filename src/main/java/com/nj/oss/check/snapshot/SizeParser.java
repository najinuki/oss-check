package com.nj.oss.check.snapshot;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses size values from _cat API rows into bytes.
 *
 * <p>collect requests {@code bytes=b}, so values are normally plain numbers,
 * but human-readable units ({@code "1.2gb"}) are also accepted so that
 * hand-assembled or externally produced dumps still parse.
 */
final class SizeParser {

    private static final Pattern HUMAN_SIZE = Pattern.compile("(\\d+(?:\\.\\d+)?)(b|kb|mb|gb|tb|pb)");

    private static final Map<String, Long> UNIT_FACTORS = Map.of(
            "b", 1L,
            "kb", 1024L,
            "mb", 1024L * 1024,
            "gb", 1024L * 1024 * 1024,
            "tb", 1024L * 1024 * 1024 * 1024,
            "pb", 1024L * 1024 * 1024 * 1024 * 1024);

    private SizeParser() {
    }

    /** Returns bytes, or null when the input is null/blank (e.g. unassigned shard rows). */
    static Long parseBytes(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Long.parseLong(normalized);
        }
        Matcher matcher = HUMAN_SIZE.matcher(normalized);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unparseable size value: \"" + value + "\"");
        }
        double amount = Double.parseDouble(matcher.group(1));
        return Math.round(amount * UNIT_FACTORS.get(matcher.group(2)));
    }
}