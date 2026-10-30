package com.overload.overload_server.workout;

import com.fasterxml.jackson.annotation.JsonCreator;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

/** Strict JSON at the write boundary: never coerce decimals, numeric strings or owner fields. */
public final class WorkoutWriteRequests {

	private WorkoutWriteRequests() { }

	public record CreateExercise(UUID id, long dataGeneration, String name) {
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static CreateExercise from(JsonNode body) {
			fields(body, Set.of("id", "dataGeneration", "name"));
			String id = text(body, "id");
			UUID uuid = UUID.fromString(id);
			if (!uuid.toString().equalsIgnoreCase(id)) throw invalid();
			return new CreateExercise(uuid, generation(body), WorkoutWriteRequests.name(body));
		}
	}

	public record RenameExercise(long dataGeneration, String name) {
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static RenameExercise from(JsonNode body) {
			fields(body, Set.of("dataGeneration", "name"));
			return new RenameExercise(generation(body), WorkoutWriteRequests.name(body));
		}
	}

	public record ChangeRest(long dataGeneration, int restLowSeconds, int restMediumSeconds, int restHighSeconds) {
		@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
		public static ChangeRest from(JsonNode body) {
			fields(body, Set.of("dataGeneration", "restLowSeconds", "restMediumSeconds", "restHighSeconds"));
			return new ChangeRest(generation(body), seconds(body, "restLowSeconds"),
					seconds(body, "restMediumSeconds"), seconds(body, "restHighSeconds"));
		}
	}

	private static void fields(JsonNode body, Set<String> names) {
		if (body == null || !body.isObject() || body.size() != names.size()) throw invalid();
		for (String field : body.propertyNames()) if (!names.contains(field)) throw invalid();
	}

	private static String text(JsonNode body, String field) {
		var value = body.get(field);
		if (value == null || !value.isString()) throw invalid();
		return value.asString();
	}

	private static String name(JsonNode body) {
		String value = text(body, "name");
		if (value.isBlank() || value.indexOf('\0') >= 0 || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) throw invalid();
		return value; // Preserve whitespace and Unicode; names are not unique identifiers.
	}

	private static long generation(JsonNode body) {
		String value = text(body, "dataGeneration");
		if (!value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
		return Long.parseLong(value);
	}

	private static int seconds(JsonNode body, String field) {
		var value = body.get(field);
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() <= 0) throw invalid();
		return value.intValue();
	}

	private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid workout write request"); }
}
