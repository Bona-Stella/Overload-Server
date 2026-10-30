package com.overload.overload_server.workoutcommand;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;

final class CommandValues {

	private static final DateTimeFormatter MILLIS = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
	private CommandValues() { }

	static UUID uuid(String value, boolean newlyGenerated) {
		if (value == null || !value.matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
			throw new IllegalArgumentException("Invalid canonical UUID");
		}
		var id = UUID.fromString(value);
		if (newlyGenerated) newId(id);
		return id;
	}

	static void newId(UUID value) {
		if (value == null || value.version() != 4 || value.variant() != 2) {
			throw new IllegalArgumentException("New identifiers require UUID v4");
		}
	}

	static Instant instant(String value) {
		if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z")) {
			throw new IllegalArgumentException("Invalid canonical command instant");
		}
		var instant = Instant.parse(value);
		if (!MILLIS.format(instant).equals(value)) throw new IllegalArgumentException("Invalid command instant");
		return instant;
	}

	static String instant(Instant value) {
		if (value == null || value.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("Millisecond instant required");
		return MILLIS.format(value);
	}

	static String text(String value) {
		if (value == null || value.isBlank() || value.indexOf(0) >= 0) throw new IllegalArgumentException("Nonblank text required");
		for (int index = 0; index < value.length(); index++) {
			char current = value.charAt(index);
			if (Character.isHighSurrogate(current)) {
				if (++index == value.length() || !Character.isLowSurrogate(value.charAt(index))) throw new IllegalArgumentException("Invalid Unicode");
			} else if (Character.isLowSurrogate(current)) throw new IllegalArgumentException("Invalid Unicode");
		}
		return value;
	}
}
