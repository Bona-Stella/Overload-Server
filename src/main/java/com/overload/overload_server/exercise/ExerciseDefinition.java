package com.overload.overload_server.exercise;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Existing V2 personal exercise; no new identity, version or deletion columns. */
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

	protected ExerciseDefinition() {
	}

	public UUID getId() { return id; }
	public UUID getUserId() { return userId; }
	public long getDataGeneration() { return dataGeneration; }
	public String getName() { return name; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
