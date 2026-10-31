package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class WorkoutSessionRequestsTests {
    private final String body = """
            {"id":"%s","dataGeneration":"9007199254740993","routineId":"%s","editorDeviceId":"%s",
             "startedAt":"2026-10-31T16:00:00.123Z","workoutDate":"2026-11-01","startedTimeZone":"Asia/Seoul"}
            """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    @Test void exactCountersAndLocalDateAcrossMidnight() {
        var result = WorkoutSessionRequests.start(body);
        assertThat(result.dataGeneration()).isEqualTo(9007199254740993L);
        assertThat(result.workoutDate().toString()).isEqualTo("2026-11-01");
    }
    @Test void malformedDuplicateUnknownNullAndTrailingJsonAreRejected() {
        for (String invalid : new String[]{body + "{}", body.replace("\"id\":", "\"other\":null,\"id\":"),
                body.replace("\"dataGeneration\":", "\"dataGeneration\":\"0\",\"dataGeneration\":"),
                body.replace("\"9007199254740993\"", "9007199254740993"), body.replace("\"9007199254740993\"", "null"),
                body.replace("\"9007199254740993\"", "\"01\""), body.replace("\"9007199254740993\"", "\"9223372036854775808\""),
                body.replace("00.123Z", "00.1234Z"), body.replace("16:00:00", "16:00:60")}) {
            assertThatThrownBy(() -> WorkoutSessionRequests.start(invalid)).isInstanceOf(WorkoutSessionFailure.class)
                    .hasMessage("INVALID_REQUEST");
        }
    }
    @Test void inconsistentDateAndUnknownZoneAreNotServerCorrected() {
        assertThatThrownBy(() -> WorkoutSessionRequests.start(body.replace("2026-11-01", "2026-10-31")))
                .hasMessage("CLOCK_CONTEXT_INVALID");
        assertThatThrownBy(() -> WorkoutSessionRequests.start(body.replace("Asia/Seoul", "+09:00")))
                .hasMessage("CLOCK_CONTEXT_INVALID");
    }
}
