package com.overload.overload_server.workoutcommand;

import com.overload.overload_server.security.SessionPrincipal;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static com.overload.overload_server.workoutcommand.CommandEnvelope.*;

/** Strict, isolated parser; does not change Spring's shared HTTP/Jackson configuration. */
public final class WorkoutCommandCodec {

	public record PreparedCommand(CommandEnvelope envelope, String canonicalJson, String fingerprint) { }

	public PreparedCommand prepare(SessionPrincipal principal, String input) {
		if (principal == null) throw new WriteContractViolation(WriteContractViolation.Code.AUTHENTICATION_REQUIRED);
		var node = CommandJson.read(input);
		var envelope = decode(node);
		if (!principal.userId().equals(envelope.expectedUserId())) {
			throw new WriteContractViolation(WriteContractViolation.Code.OWNER_CONTEXT_MISMATCH);
		}
		var canonical = CommandJson.canonicalize(node);
		try {
			var digest = MessageDigest.getInstance("SHA-256").digest(("overload-command-v1\n" + principal.userId()
					+ "\n" + canonical).getBytes(StandardCharsets.UTF_8));
			return new PreparedCommand(envelope, canonical, HexFormat.of().formatHex(digest));
		} catch (NoSuchAlgorithmException failure) { throw new IllegalStateException("SHA-256 unavailable", failure); }
	}

	/** Typed decoding for persisted metadata; empty {} denotes an unobserved intent. */
	public Map<String, IntentStamp> readStamps(ResourceType resourceType, String input) {
		var node = CommandJson.read(input);
		if (!node.isObject()) throw invalid();
		var result = new TreeMap<String, IntentStamp>();
		for (var property : node.properties()) {
			if (!resourceType.allows(property.getKey()) || property.getValue().isNull()) throw invalid();
			result.put(property.getKey(), stamp(property.getValue()));
		}
		return Map.copyOf(result);
	}

	private CommandEnvelope decode(JsonNode node) {
		fields(node, "commandSchemaVersion", "operationId", "expectedUserId", "dataGeneration", "type",
				"expectedVersions", "fieldBases", "sessionControl", "occurredAt", "payload");
		var type = CommandType.valueOf(text(node, "type"));
		var versions = new ArrayList<ExpectedVersion>();
		for (var value : array(node, "expectedVersions")) {
			fields(value, "resourceType", "resourceId", "version");
			versions.add(new ExpectedVersion(resourceType(value), id(value, "resourceId", false), new Counter(text(value, "version"))));
		}
		var bases = new ArrayList<FieldBase>();
		for (var value : array(node, "fieldBases")) {
			fields(value, "resourceType", "resourceId", "fieldKey", "baseStamp");
			bases.add(new FieldBase(resourceType(value), id(value, "resourceId", false), text(value, "fieldKey"),
					value.get("baseStamp").isNull() ? null : stamp(value.get("baseStamp"))));
		}
		SessionControl control = null;
		if (!node.get("sessionControl").isNull()) {
			var value = node.get("sessionControl");
			fields(value, "deviceId", "ownershipEpoch", "deviceBindingRef");
			control = new SessionControl(id(value, "deviceId", true), new Counter(text(value, "ownershipEpoch")), text(value, "deviceBindingRef"));
		}
		var envelope = new CommandEnvelope(integer(node, "commandSchemaVersion"), id(node, "operationId", true),
				id(node, "expectedUserId", false), new Counter(text(node, "dataGeneration")), type,
				versions, bases, control, CommandValues.instant(text(node, "occurredAt")), payload(type, node.get("payload")));
		return envelope;
	}

	private Payload payload(CommandType type, JsonNode node) {
		return switch (type) {
			case CREATE_EXERCISE -> {
				fields(node, "id", "name");
				yield new CreateExercise(id(node, "id", true), text(node, "name"));
			}
			case RENAME_EXERCISE -> {
				fields(node, "exerciseId", "name");
				yield new RenameExercise(id(node, "exerciseId", false), text(node, "name"));
			}
			case UPDATE_WORKOUT_SETTINGS -> {
				fields(node, "changes");
				var changes = new ArrayList<RestChange>();
				for (var change : array(node, "changes")) {
					fields(change, "field", "value");
					changes.add(new RestChange(text(change, "field"), integer(change, "value")));
				}
				yield new UpdateSettings(changes);
			}
			case RESOLVE_WRITE_CONFLICT -> {
				fields(node, "originOperationId", "originFingerprint", "mode", "choices");
				var choices = new ArrayList<ConflictChoice>();
				for (var choice : array(node, "choices")) {
					fields(choice, "resourceType", "resourceId", "fieldKey", "action", "value");
					var value = choice.get("value");
					if (!value.isNull() && !value.isString() && !value.isIntegralNumber()) throw invalid();
					choices.add(new ConflictChoice(resourceType(choice), id(choice, "resourceId", false), text(choice, "fieldKey"),
							ChoiceAction.valueOf(text(choice, "action")), value.isString() ? value.asString() : null,
							value.isIntegralNumber() ? exactInteger(value) : null));
				}
				yield new ResolveConflict(id(node, "originOperationId", true), text(node, "originFingerprint"),
						ResolutionMode.valueOf(text(node, "mode")), choices);
			}
		};
	}

	private static IntentStamp stamp(JsonNode node) {
		fields(node, "occurredAt", "originOperationId");
		return new IntentStamp(CommandValues.instant(text(node, "occurredAt")), id(node, "originOperationId", true));
	}

	private static ResourceType resourceType(JsonNode node) { return ResourceType.valueOf(text(node, "resourceType")); }
	private static UUID id(JsonNode node, String key, boolean fresh) { return CommandValues.uuid(text(node, key), fresh); }
	private static int integer(JsonNode node, String key) { return exactInteger(node.get(key)); }
	private static int exactInteger(JsonNode value) {
		if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw invalid();
		return value.intValue();
	}
	private static String text(JsonNode node, String key) {
		var value = node.get(key);
		if (value == null || !value.isString()) throw invalid();
		return value.asString();
	}
	private static List<JsonNode> array(JsonNode node, String key) {
		var value = node.get(key);
		if (value == null || !value.isArray()) throw invalid();
		var result = new ArrayList<JsonNode>();
		value.forEach(result::add);
		return result;
	}
	private static void fields(JsonNode node, String... allowed) {
		if (node == null || !node.isObject() || node.size() != allowed.length
				|| !node.properties().stream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()).equals(Set.of(allowed))) throw invalid();
	}
	private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid workout command contract"); }
}
