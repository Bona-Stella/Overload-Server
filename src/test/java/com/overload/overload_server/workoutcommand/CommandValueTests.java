package com.overload.overload_server.workoutcommand;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class CommandValueTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@ParameterizedTest
	@ValueSource(strings = {"0", "9007199254740993", "9223372036854775807"})
	void countersRoundTripAsStringsWithoutJsNumberLoss(String value) {
		var counter = new Counter(value);
		assertThat(JSON.writeValueAsString(counter)).isEqualTo('"' + value + '"');
		assertThat(JSON.readValue('"' + value + '"', Counter.class)).isEqualTo(counter);
		assertThat(counter.longValue()).isEqualTo(Long.parseLong(value));
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "00", "01", "-1", "+1", "1.0", "1e2", " 1", "9223372036854775808", "99999999999999999999999"})
	void invalidCountersAreRejected(String value) {
		assertThatThrownBy(() -> new Counter(value)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void counterIncrementCannotWrap() {
		assertThat(Counter.of(0).next()).isEqualTo(Counter.of(1));
		assertThatThrownBy(() -> Counter.of(Long.MAX_VALUE).next()).isInstanceOf(ArithmeticException.class);
		assertThatThrownBy(() -> Counter.of(-1)).isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {"0", "60", "62.5", "0.000001", "99999999999999.999999"})
	void exactWeightsRoundTripAsStrings(String value) {
		var weight = new WeightKg(value);
		assertThat(weight.decimalValue()).isEqualByComparingTo(new BigDecimal(value));
		assertThat(JSON.readValue(JSON.writeValueAsString(weight), WeightKg.class)).isEqualTo(weight);
		assertThat(WeightKg.of(weight.decimalValue())).isEqualTo(weight);
	}

	@ParameterizedTest
	@ValueSource(strings = {"", "-0", "-1", "+1", "01", ".1", "1.", "1.0", "0.0000001", "100000000000000", "1e2", "NaN", "Infinity", " 60"})
	void malformedOrOutOfRangeDecimalDoesNotReachJdbc(String value) {
		assertThatThrownBy(() -> new WeightKg(value)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void bigDecimalConversionDoesNotRound() {
		assertThat(WeightKg.of(new BigDecimal("60.000000")).value()).isEqualTo("60");
		assertThatThrownBy(() -> WeightKg.of(new BigDecimal("1.2345678"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> WeightKg.of(new BigDecimal("1.0000000"))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> WeightKg.of(new BigDecimal("1E+100"))).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void intentOrderingUsesDeviceTimeThenCanonicalUuidNotUploadTime() {
		var low = UUID.fromString("00000000-0000-4000-8000-000000000001");
		var high = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff");
		var monday = new IntentStamp(Instant.parse("2026-10-26T10:00:00Z"), high);
		var tuesday = new IntentStamp(Instant.parse("2026-10-27T10:00:00Z"), low);
		assertThat(monday.compareTo(tuesday)).isNegative();
		assertThat(new IntentStamp(tuesday.occurredAt(), high).compareTo(tuesday)).isPositive();
		assertThatThrownBy(() -> new IntentStamp(Instant.parse("2026-10-27T10:00:00.000001Z"), low))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
