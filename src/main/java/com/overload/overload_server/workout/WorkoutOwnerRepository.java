package com.overload.overload_server.workout;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.OptionalLong;
import java.util.UUID;

/** Uses the connection already bound by the workout JPA transaction; never locks or provisions an account. */
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
}
