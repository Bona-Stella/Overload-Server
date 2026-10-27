package com.overload.overload_server.account;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountSeedTests {

	@Test
	void containsExactlyTheApprovedTwentyWeightExercises() {
		assertThat(AccountSeed.WEIGHT_EXERCISE_NAMES).containsExactly(
				"벤치프레스", "인클라인 덤벨 프레스", "체스트 프레스", "케이블 플라이",
				"랫풀다운", "시티드 케이블 로우", "바벨 로우", "풀업",
				"스쿼트", "레그 프레스", "레그 익스텐션", "레그 컬", "루마니안 데드리프트",
				"숄더 프레스", "사이드 레터럴 레이즈", "리어 델트 플라이",
				"바벨 컬", "덤벨 컬", "케이블 푸시다운", "오버헤드 트라이셉스 익스텐션");
	}

	@ParameterizedTest
	@CsvSource({"1,180", "10,180", "11,120", "12,120", "13,90", "2147483647,90"})
	void selectsRestFromTheTargetUpperBound(int maximumReps, int seconds) {
		assertThat(AccountSeed.WORKOUT_SETTINGS.restSecondsFor(maximumReps)).isEqualTo(seconds);
	}

	@Test
	void rejectsNonpositiveSettingsAndRepetitionBounds() {
		assertThatThrownBy(() -> new WorkoutSettings(0, 120, 90)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkoutSettings(180, -1, 90)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new WorkoutSettings(180, 120, 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AccountSeed.WORKOUT_SETTINGS.restSecondsFor(0))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AccountSeed.WORKOUT_SETTINGS.restSecondsFor(-1))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
