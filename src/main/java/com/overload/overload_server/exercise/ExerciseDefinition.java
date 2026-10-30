package com.overload.overload_server.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/** Account-owned exercise; never serialized as an API DTO. */
@Entity
@Table(name = "exercise_definitions")
public class ExerciseDefinition {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "data_generation", nullable = false, updatable = false)
	private long dataGeneration;

	@Column(name = "name", nullable = false, columnDefinition = "text")
	private String name;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;

	@Column(name = "deleted_at")
	private Instant deletedAt;

	protected ExerciseDefinition() {
	}

	public static ExerciseDefinition create(UUID id, UUID userId, long generation, String name, Instant now) {
		var exercise = new ExerciseDefinition();
		exercise.id = id;
		exercise.userId = userId;
		exercise.dataGeneration = generation;
		exercise.name = name;
		exercise.createdAt = now;
		exercise.updatedAt = now;
		return exercise;
	}

	public void rename(String name, Instant now) {
		if (!this.name.equals(name)) {
			this.name = name;
			this.updatedAt = now;
		}
	}

	public UUID getId() { return id; }
	public UUID getUserId() { return userId; }
	public long getDataGeneration() { return dataGeneration; }
	public String getName() { return name; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
	public Long getVersion() { return version; }
	public Instant getDeletedAt() { return deletedAt; }
}
