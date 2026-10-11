package io.github.mcagentsmods.chat.common.storage.sqlite;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoField;
import java.util.Locale;

/** Conversions between the shapes SQLite stores and the types the API models use. */
final class SqlValues {

    /**
     * What {@code CURRENT_TIMESTAMP} produces: {@code YYYY-MM-DD HH:MM:SS} in UTC,
     * optionally with a fractional part.
     */
    private static final DateTimeFormatter TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter(Locale.ROOT);

    private SqlValues() {
        throw new AssertionError("No instances.");
    }

    /**
     * Reads a timestamp column.
     *
     * <p>SQLite has no date type, so the column holds text. A row written by this mod is
     * always in the format above; a row written by anything else might be ISO-8601, so
     * that is tried second. A value that is neither is treated as "unknown" and reported
     * as the epoch rather than failing the whole query — a bad timestamp must not make a
     * player's chat history unreadable.
     */
    static Instant readInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        String trimmed = value.trim();
        try {
            return LocalDateTime.parse(trimmed, TIMESTAMP).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException primary) {
            try {
                return Instant.parse(trimmed);
            } catch (DateTimeParseException secondary) {
                return Instant.EPOCH;
            }
        }
    }

    /**
     * Escapes a user's search text for a {@code LIKE ? ESCAPE '\'} clause and wraps it in
     * wildcards, so typing {@code 100%} searches for that text instead of matching
     * everything.
     */
    static String containsPattern(String text) {
        String escaped = text
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
