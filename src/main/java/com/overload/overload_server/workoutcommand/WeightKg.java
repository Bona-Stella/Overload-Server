package com.overload.overload_server.workoutcommand;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** NUMERIC(20,6) input contract. Validate before JDBC: PostgreSQL casts may round. */
public record WeightKg(@JsonValue String value) {

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public WeightKg {
		if (value == null || !value.matches("(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{0,5}[1-9])?")) {
			throw new IllegalArgumentException("Invalid canonical kg decimal");
		}
	}

	public BigDecimal decimalValue() { return new BigDecimal(value); }

	public static WeightKg of(BigDecimal value) {
		if (value == null || value.signum() < 0 || value.scale() > 6
				|| (long) value.precision() - value.scale() > 14) {
			throw new IllegalArgumentException("Weight exceeds NUMERIC(20,6)");
		}
		return new WeightKg(value.setScale(6, RoundingMode.UNNECESSARY).stripTrailingZeros().toPlainString());
	}
}
