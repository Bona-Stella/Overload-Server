package com.overload.overload_server.workoutcommand;

import com.overload.overload_server.security.SessionPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static com.overload.overload_server.workoutcommand.CommandEnvelope.*;

class WorkoutCommandCodecTests {

	static final UUID OWNER = UUID.fromString("11111111-1111-4111-8111-111111111111");
	static final UUID OPERATION = UUID.fromString("22222222-2222-4222-8222-222222222222");
	static final UUID EXERCISE = UUID.fromString("33333333-3333-4333-8333-333333333333");
	static final SessionPrincipal PRINCIPAL = new SessionPrincipal(OWNER);
	static final WorkoutCommandCodec CODEC = new WorkoutCommandCodec();

	static String create() {
		return """
				{"commandSchemaVersion":1,"operationId":"%s","expectedUserId":"%s","dataGeneration":"9007199254740993",
				 "type":"CREATE_EXERCISE","expectedVersions":[],"fieldBases":[],"sessionControl":null,
				 "occurredAt":"2026-10-30T00:00:00.000Z","payload":{"id":"%s","name":"  벤치 😀  "}}
				""".formatted(OPERATION, OWNER, EXERCISE);
	}

	static String rename() {
		return create().replace("CREATE_EXERCISE", "RENAME_EXERCISE")
				.replace("\"expectedVersions\":[]", "\"expectedVersions\":[{\"resourceType\":\"EXERCISE\",\"resourceId\":\"" + EXERCISE + "\",\"version\":\"0\"}]")
				.replace("\"fieldBases\":[]", "\"fieldBases\":[{\"resourceType\":\"EXERCISE\",\"resourceId\":\"" + EXERCISE + "\",\"fieldKey\":\"name\",\"baseStamp\":null}]")
				.replace("\"id\":", "\"exerciseId\":");
	}

	@Test
	void canonicalWireOrderAndWhitespaceDoNotChangeFingerprint() {
		var command = CODEC.prepare(PRINCIPAL, create());
		assertThat(command.envelope().payload()).isEqualTo(new CreateExercise(EXERCISE, "  벤치 😀  "));
		assertThat(command.envelope().dataGeneration().value()).isEqualTo("9007199254740993");
		assertThat(CODEC.prepare(PRINCIPAL, command.canonicalJson()).fingerprint()).isEqualTo(command.fingerprint());
		assertThat(command.canonicalJson()).startsWith("{\"commandSchemaVersion\":1,\"dataGeneration\":\"9007199254740993\"");
		// Independently calculated with Node.js crypto + UTF-16 sorted properties; see 1A report.
		assertThat(command.fingerprint()).isEqualTo("f2586eb4f0074fb4754ce6499e12ba0f4fa1cb951d04976ec20709c8064544a4");
		assertThat(CODEC.prepare(PRINCIPAL, create().replace("  벤치 😀  ", "벤치 😀")).fingerprint()).isNotEqualTo(command.fingerprint());
	}

	@Test
	void fingerprintCoversVersionFieldBasesOperationAndEventTime() {
		var original = CODEC.prepare(PRINCIPAL, rename());
		for (var changed : java.util.List.of(
				rename().replace("\"version\":\"0\"", "\"version\":\"1\""),
				rename().replace(OPERATION.toString(), UUID.randomUUID().toString()),
				rename().replace("2026-10-30T00:00:00.000Z", "2026-10-30T00:00:00.001Z"),
				rename().replace("\"baseStamp\":null", "\"baseStamp\":{\"occurredAt\":\"2026-10-29T00:00:00.000Z\",\"originOperationId\":\"" + UUID.randomUUID() + "\"}"))) {
			assertThat(CODEC.prepare(PRINCIPAL, changed).fingerprint()).isNotEqualTo(original.fingerprint());
		}
	}

	@Test
	void accountSwitchPreconditionIsNotAnOwnerClaim() {
		assertThatThrownBy(() -> CODEC.prepare(new SessionPrincipal(UUID.randomUUID()), create()))
				.isInstanceOfSatisfying(WriteContractViolation.class,
						failure -> assertThat(failure.code()).isEqualTo(WriteContractViolation.Code.OWNER_CONTEXT_MISMATCH));
		assertThatThrownBy(() -> CODEC.prepare(null, create())).isInstanceOf(WriteContractViolation.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"duplicate", "unknown", "missing", "numericCounter", "nonV4", "uppercase", "badTime", "decimalSchema", "wrongPayload", "extraOwner", "trailing"})
	void malformedCommandsFailBeforePersistence(String kind) {
		var input = switch (kind) {
			case "duplicate" -> create().replace("\"commandSchemaVersion\":1", "\"commandSchemaVersion\":1,\"commandSchemaVersion\":1");
			case "unknown" -> create().replace("\"commandSchemaVersion\":1", "\"unknown\":null,\"commandSchemaVersion\":1");
			case "missing" -> create().replace("\"sessionControl\":null,", "");
			case "numericCounter" -> create().replace("\"9007199254740993\"", "9007199254740993");
			case "nonV4" -> create().replace(OPERATION.toString(), "22222222-2222-1222-8222-222222222222");
			case "uppercase" -> create().replace(EXERCISE.toString(), "ABCDEFAB-3333-4333-8333-333333333333");
			case "badTime" -> create().replace("00:00:00.000Z", "00:00:00Z");
			case "decimalSchema" -> create().replace("\"commandSchemaVersion\":1", "\"commandSchemaVersion\":1.0");
			case "wrongPayload" -> create().replace("\"id\":", "\"exerciseId\":");
			case "extraOwner" -> create().replace("\"payload\":{", "\"payload\":{\"userId\":\"" + OWNER + "\",");
			default -> create() + " {}";
		};
		assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, input)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void fieldBasesMustExactlyMatchTypedDeltaAndBeUnique() {
		assertThat(CODEC.prepare(PRINCIPAL, rename()).envelope().fieldBases()).hasSize(1);
		for (var changed : java.util.List.of(rename().replace("\"fieldKey\":\"name\"", "\"fieldKey\":\"lifecycle\""),
				rename().replace("\"EXERCISE\"", "\"ROUTINE\""),
				rename().replace("\"expectedVersions\":[", "\"expectedVersions\":[{\"resourceType\":\"EXERCISE\",\"resourceId\":\"" + EXERCISE + "\",\"version\":\"0\"},"))) {
			assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, changed)).isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void semanticStampMetadataIsTypedWithoutClockSkewRejection() {
		var stamps = CODEC.readStamps(ResourceType.EXERCISE,
				"{\"name\":{\"occurredAt\":\"2099-01-01T00:00:00.000Z\",\"originOperationId\":\"" + OPERATION + "\"}}");
		assertThat(stamps.get("name").originOperationId()).isEqualTo(OPERATION);
		assertThat(CODEC.readStamps(ResourceType.EXERCISE, "{}")).isEmpty();
		assertThatThrownBy(() -> CODEC.readStamps(ResourceType.EXERCISE, "{\"arbitrary\":null}")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void settingsAreTypedFieldChangesAndArrayOrderRemainsInFingerprint() {
		var input = settings();
		var prepared = CODEC.prepare(PRINCIPAL, input);
		assertThat(prepared.envelope().payload()).isEqualTo(new UpdateSettings(java.util.List.of(
				new RestChange("restLowSeconds", 17), new RestChange("restHighSeconds", 43))));
		var reversed = input.replace("[{\"field\":\"restLowSeconds\",\"value\":17},{\"field\":\"restHighSeconds\",\"value\":43}]",
				"[{\"field\":\"restHighSeconds\",\"value\":43},{\"field\":\"restLowSeconds\",\"value\":17}]");
		assertThat(CODEC.prepare(PRINCIPAL, reversed).fingerprint()).isNotEqualTo(prepared.fingerprint());
		for (var invalid : java.util.List.of(input.replace("\"value\":17", "\"value\":0"),
				input.replace("\"value\":17", "\"value\":2147483648"),
				input.replace("\"value\":17", "\"value\":\"17\""),
				input.replace("\"field\":\"restHighSeconds\"", "\"field\":\"restLowSeconds\""),
				input.replace(OWNER.toString(), EXERCISE.toString()))) {
			assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, invalid)).isInstanceOfAny(IllegalArgumentException.class, WriteContractViolation.class);
		}
	}

	@Test
	void resolutionHasNewOperationAndImmutableOriginReferenceButNoExecution() {
		var origin = UUID.randomUUID();
		var input = rename().replace("RENAME_EXERCISE", "RESOLVE_WRITE_CONFLICT");
		var node = tools.jackson.databind.json.JsonMapper.builder().build().readTree(input).asObject();
		node.putArray("fieldBases");
		var payload = node.putObject("payload");
		payload.put("originOperationId", origin.toString()); payload.put("originFingerprint", "a".repeat(64));
		payload.put("mode", "AUTO"); payload.putArray("choices");
		var prepared = CODEC.prepare(PRINCIPAL, node.toString());
		assertThat(prepared.envelope().payload()).isEqualTo(new ResolveConflict(origin, "a".repeat(64), ResolutionMode.AUTO, java.util.List.of()));
		payload.put("originOperationId", OPERATION.toString());
		assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, node.toString())).isInstanceOf(IllegalArgumentException.class);
		payload.put("originOperationId", origin.toString()); payload.put("mode", "USER_CHOICE");
		assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, node.toString())).isInstanceOf(IllegalArgumentException.class);
		var choice = payload.withArray("choices").addObject();
		choice.put("resourceType", "EXERCISE"); choice.put("resourceId", EXERCISE.toString());
		choice.put("fieldKey", "name"); choice.put("action", "SET_VALUE"); choice.put("value", "chosen");
		assertThat(((ResolveConflict) CODEC.prepare(PRINCIPAL, node.toString()).envelope().payload()).mode()).isEqualTo(ResolutionMode.USER_CHOICE);
		choice.put("fieldKey", "lifecycle");
		assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, node.toString())).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void legacyReferencedUuidsArePreservedAndNeverReissued() {
		var legacy = "33333333-3333-1333-8333-333333333333";
		assertThat(((RenameExercise) CODEC.prepare(PRINCIPAL, rename().replace(EXERCISE.toString(), legacy)).envelope().payload()).exerciseId().toString()).isEqualTo(legacy);
		assertThatThrownBy(() -> CODEC.prepare(PRINCIPAL, create().replace(EXERCISE.toString(), legacy))).isInstanceOf(IllegalArgumentException.class);
	}

	private String settings() {
		return """
				{"commandSchemaVersion":1,"operationId":"%s","expectedUserId":"%s","dataGeneration":"0","type":"UPDATE_WORKOUT_SETTINGS",
				 "expectedVersions":[{"resourceType":"WORKOUT_SETTINGS","resourceId":"%s","version":"0"}],
				 "fieldBases":[{"resourceType":"WORKOUT_SETTINGS","resourceId":"%s","fieldKey":"restHighSeconds","baseStamp":null},
				 {"resourceType":"WORKOUT_SETTINGS","resourceId":"%s","fieldKey":"restLowSeconds","baseStamp":null}],
				 "sessionControl":null,"occurredAt":"2026-10-30T00:00:00.000Z",
				 "payload":{"changes":[{"field":"restLowSeconds","value":17},{"field":"restHighSeconds","value":43}]}}
				""".formatted(OPERATION, OWNER, OWNER, OWNER, OWNER);
	}

	@Test
	void utf16SortingEscapingAndUnicodeAreJcsCompatibleForCommandPrimitives() {
		var input = "{\"€\":1,\"😀\":2,\"ö\":3,\"1\":4,\"\\r\":5,\"\\u0080\":6,\"דּ\":7}";
		assertThat(CommandJson.canonicalize(input)).isEqualTo("{\"\\r\":5,\"1\":4,\"\u0080\":6,\"ö\":3,\"€\":1,\"😀\":2,\"דּ\":7}");
		assertThat(CommandJson.canonicalize("{\"s\":\"\\u000f\\n\\\"\\\\/\"}"))
				.isEqualTo("{\"s\":\"\\u000f\\n\\\"\\\\/\"}");
		assertThat(CommandJson.canonicalize("\"é\"")).isNotEqualTo(CommandJson.canonicalize("\"é\""));
	}

	@ParameterizedTest
	@ValueSource(strings = {"{\"a\":1,\"a\":2}", "{\"nested\":{\"a\":1,\"a\":2}}", "1.5", "1e0", "9007199254740992", "NaN", "\"\\ud800\"", "\"\\u0000\""})
	void unsupportedOrAmbiguousJsonCannotBeFingerprintInput(String json) {
		assertThatThrownBy(() -> CommandJson.canonicalize(json)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void arraysAreNotReorderedAndParserHasResourceBounds() {
		assertThat(CommandJson.canonicalize("{\"a\":[2,1]}" )).isEqualTo("{\"a\":[2,1]}");
		assertThatThrownBy(() -> CommandJson.canonicalize("[".repeat(65) + "0" + "]".repeat(65))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CommandJson.canonicalize("\"" + "한".repeat(CommandJson.MAX_BYTES / 2) + "\""))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
