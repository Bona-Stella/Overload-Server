package com.overload.overload_server.workoutsettings;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Existing V2 settings: the owning user UUID is the primary key. */
@Entity
@Table(name = "user_workout_settings")
public class UserWorkoutSettings {

	@Id
	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "data_generation", nullable = false, updatable = false)
	private long dataGeneration;

	@Column(name = "rest_low_seconds", nullable = false)
	private int restLowSeconds;

	@Column(name = "rest_medium_seconds", nullable = false)
	private int restMediumSeconds;

	@Column(name = "rest_high_seconds", nullable = false)
	private int restHighSeconds;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected UserWorkoutSettings() {
	}

	public UUID getUserId() { return userId; }
	public long getDataGeneration() { return dataGeneration; }
	public int getRestLowSeconds() { return restLowSeconds; }
	public int getRestMediumSeconds() { return restMediumSeconds; }
	public int getRestHighSeconds() { return restHighSeconds; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
