package com.overload.overload_server.workout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.OptionalLong;
import java.util.UUID;

/** Uses the connection bound by the workout JPA transaction; never provisions an account. */
@Repository
class WorkoutOwnerRepository {

	private final JdbcTemplate jdbc;

	WorkoutOwnerRepository(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	OptionalLong findGeneration(UUID userId) {
		var values = jdbc.query("SELECT data_generation FROM users WHERE id = ?", (row, index) -> row.getLong(1), userId);
		return values.isEmpty() ? OptionalLong.empty() : OptionalLong.of(values.getFirst());
	}

	OptionalLong findGenerationForWrite(UUID userId) {
		// Shared locks allow parallel writes but prevent owner deletion/generation change until commit.
		var values = jdbc.query("SELECT data_generation FROM users WHERE id = ? FOR SHARE",
				(row, index) -> row.getLong(1), userId);
		return values.isEmpty() ? OptionalLong.empty() : OptionalLong.of(values.getFirst());
	}
}
