package com.overload.overload_server.workoutcommand;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Wire contract only: no dispatcher, HTTP endpoint or domain mutation is installed in 1A. */
public record CommandEnvelope(int commandSchemaVersion, UUID operationId, UUID expectedUserId,
		Counter dataGeneration, CommandType type, List<ExpectedVersion> expectedVersions,
		List<FieldBase> fieldBases, SessionControl sessionControl, Instant occurredAt, Payload payload) {

	public CommandEnvelope {
		if (commandSchemaVersion != 1) throw new IllegalArgumentException("Unsupported command schema");
		CommandValues.newId(operationId);
		Objects.requireNonNull(expectedUserId);
		Objects.requireNonNull(dataGeneration);
		Objects.requireNonNull(type);
		Objects.requireNonNull(payload);
		if (payload.type() != type) throw new IllegalArgumentException("Command payload type mismatch");
		if (payload instanceof ResolveConflict resolution && resolution.originOperationId().equals(operationId)) {
			throw new IllegalArgumentException("Resolution requires a new operationId");
		}
		CommandValues.instant(occurredAt);
		expectedVersions = List.copyOf(expectedVersions);
		fieldBases = List.copyOf(fieldBases);
		strictlySorted(expectedVersions.stream().map(ExpectedVersion::key).toList());
		strictlySorted(fieldBases.stream().map(FieldBase::key).toList());
		// The first DTO types have no session writes. Future session DTOs must explicitly opt in.
		if (sessionControl != null) throw new IllegalArgumentException("Session command types are not installed");
		validateTargets(type, payload, expectedUserId, expectedVersions, fieldBases);
	}

	public enum CommandType { CREATE_EXERCISE, RENAME_EXERCISE, UPDATE_WORKOUT_SETTINGS, RESOLVE_WRITE_CONFLICT }

	/** Format is shared with future domains, without creating their entities or handlers. */
	public enum ResourceType {
		EXERCISE, WORKOUT_SETTINGS, ROUTINE, WEEKLY_SCHEDULE, EXERCISE_MEMO, WORKOUT_SESSION, WORKOUT_SYNC_PREFERENCES;

		public boolean allows(String fieldKey) {
			return switch (this) {
				case EXERCISE -> Set.of("name", "lifecycle").contains(fieldKey);
				case WORKOUT_SETTINGS -> Set.of("restLowSeconds", "restMediumSeconds", "restHighSeconds").contains(fieldKey);
				// Future domain field vocabularies are deliberately not accepted yet.
				default -> false;
			};
		}
	}

	public record ExpectedVersion(ResourceType resourceType, UUID resourceId, Counter version) {
		public ExpectedVersion { Objects.requireNonNull(resourceType); Objects.requireNonNull(resourceId); Objects.requireNonNull(version); }
		String key() { return resourceType.name() + "/" + resourceId; }
	}

	public record FieldBase(ResourceType resourceType, UUID resourceId, String fieldKey, IntentStamp baseStamp) {
		public FieldBase {
			Objects.requireNonNull(resourceType); Objects.requireNonNull(resourceId);
			if (!resourceType.allows(fieldKey)) throw new IllegalArgumentException("Unsupported semantic field group");
		}
		String key() { return resourceType.name() + "/" + resourceId + "/" + fieldKey; }
	}

	public record SessionControl(UUID deviceId, Counter ownershipEpoch, String deviceBindingRef) {
		public SessionControl {
			CommandValues.newId(deviceId); Objects.requireNonNull(ownershipEpoch); CommandValues.text(deviceBindingRef);
		}
	}

	public sealed interface Payload permits CreateExercise, RenameExercise, UpdateSettings, ResolveConflict {
		CommandType type();
	}

	public record CreateExercise(UUID id, String name) implements Payload {
		public CreateExercise { CommandValues.newId(id); CommandValues.text(name); }
		@Override public CommandType type() { return CommandType.CREATE_EXERCISE; }
	}

	public record RenameExercise(UUID exerciseId, String name) implements Payload {
		public RenameExercise { Objects.requireNonNull(exerciseId); CommandValues.text(name); }
		@Override public CommandType type() { return CommandType.RENAME_EXERCISE; }
	}

	public record RestChange(String field, int value) {
		public RestChange {
			if (!ResourceType.WORKOUT_SETTINGS.allows(field) || value <= 0) throw new IllegalArgumentException("Invalid rest change");
		}
	}

	public record UpdateSettings(List<RestChange> changes) implements Payload {
		public UpdateSettings {
			changes = List.copyOf(changes);
			if (changes.isEmpty() || changes.stream().map(RestChange::field).distinct().count() != changes.size()) {
				throw new IllegalArgumentException("Rest fields must be nonempty and unique");
			}
		}
		@Override public CommandType type() { return CommandType.UPDATE_WORKOUT_SETTINGS; }
	}

	public enum ResolutionMode { AUTO, USER_CHOICE }
	public enum ChoiceAction { KEEP_CURRENT, APPLY_INCOMING, SET_VALUE }

	public record ConflictChoice(ResourceType resourceType, UUID resourceId, String fieldKey,
			ChoiceAction action, String textValue, Integer integerValue) {
		public ConflictChoice {
			Objects.requireNonNull(resourceType); Objects.requireNonNull(resourceId); Objects.requireNonNull(action);
			if (!resourceType.allows(fieldKey) || fieldKey.equals("lifecycle")) throw new IllegalArgumentException("Unsupported conflict choice");
			if (action == ChoiceAction.SET_VALUE) {
				if (resourceType == ResourceType.EXERCISE) {
					CommandValues.text(textValue);
					if (integerValue != null) throw new IllegalArgumentException("Unexpected integer choice");
				} else if (textValue != null || integerValue == null || integerValue <= 0) {
					throw new IllegalArgumentException("Invalid integer choice");
				}
			} else if (textValue != null || integerValue != null) throw new IllegalArgumentException("Unexpected choice value");
		}
		String key() { return resourceType.name() + "/" + resourceId + "/" + fieldKey; }
	}

	public record ResolveConflict(UUID originOperationId, String originFingerprint, ResolutionMode mode,
			List<ConflictChoice> choices) implements Payload {
		public ResolveConflict {
			CommandValues.newId(originOperationId); Objects.requireNonNull(mode);
			if (originFingerprint == null || !originFingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid origin fingerprint");
			choices = List.copyOf(choices);
			strictlySorted(choices.stream().map(ConflictChoice::key).toList());
			if ((mode == ResolutionMode.AUTO) != choices.isEmpty()) throw new IllegalArgumentException("Resolution choices do not match mode");
		}
		@Override public CommandType type() { return CommandType.RESOLVE_WRITE_CONFLICT; }
	}

	private static void strictlySorted(List<String> keys) {
		for (int index = 1; index < keys.size(); index++) {
			if (keys.get(index - 1).compareTo(keys.get(index)) >= 0) throw new IllegalArgumentException("Duplicate or unsorted command keys");
		}
	}

	private static void validateTargets(CommandType type, Payload payload, UUID userId,
			List<ExpectedVersion> versions, List<FieldBase> fields) {
		if (type == CommandType.CREATE_EXERCISE) {
			if (!versions.isEmpty() || !fields.isEmpty()) throw new IllegalArgumentException("Creation has no existing root precondition");
			return;
		}
		if (type == CommandType.RESOLVE_WRITE_CONFLICT) {
			if (versions.isEmpty() || !fields.isEmpty()) throw new IllegalArgumentException("Resolution requires current root versions; original field bases come from receipt");
			for (var version : versions) {
				if (Set.of(ResourceType.WORKOUT_SETTINGS, ResourceType.WEEKLY_SCHEDULE, ResourceType.WORKOUT_SYNC_PREFERENCES)
						.contains(version.resourceType()) && !version.resourceId().equals(userId)) {
					throw new IllegalArgumentException("Incorrect account resource precondition");
				}
			}
			return;
		}
		var resourceType = type == CommandType.RENAME_EXERCISE ? ResourceType.EXERCISE : ResourceType.WORKOUT_SETTINGS;
		var resourceId = payload instanceof RenameExercise rename ? rename.exerciseId() : userId;
		if (versions.size() != 1 || versions.getFirst().resourceType() != resourceType
				|| !versions.getFirst().resourceId().equals(resourceId)) throw new IllegalArgumentException("Incorrect expected root");
		var touched = payload instanceof RenameExercise ? Set.of("name")
				: ((UpdateSettings) payload).changes().stream().map(RestChange::field).collect(java.util.stream.Collectors.toSet());
		if (fields.size() != touched.size() || fields.stream().anyMatch(field -> field.resourceType() != resourceType
				|| !field.resourceId().equals(resourceId) || !touched.contains(field.fieldKey()))) {
			throw new IllegalArgumentException("Field bases must match the typed changes");
		}
	}
}
