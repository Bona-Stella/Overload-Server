package com.overload.overload_server.workoutsession;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class WorkoutSnapshotRequestsTests {
    static final String ID="11111111-1111-4111-8111-111111111111";
    static String body(String revision,String count) {
        return """
                {"dataGeneration":"0","editorDeviceId":"%s","writerEpoch":"0","localRevision":%s,
                "snapshot":{"sessionExercises":[],"appliedPlan":{"schemaVersion":1,"weightExercises":[{"sessionExerciseId":"%s","setCount":%s,"minReps":6,"maxReps":10}],"cardioActivities":[]},
                "performedSets":[],"cardioResults":[],"deletedPerformedSetIds":[],"deletedCardioResultIds":[],"approvalRequests":[]}}
                """.formatted(ID,revision,ID,count);
    }
    @ParameterizedTest @ValueSource(strings={"1","\"0\"","\"01\"","\"-1\"","\"9223372036854775808\"","null"})
    void rejectsInvalidRevision(String revision) { assertThatThrownBy(()->WorkoutSnapshotRequests.save(body(revision,"3"))).isInstanceOf(WorkoutSessionFailure.class); }
    @ParameterizedTest @ValueSource(strings={"0","-1","1.5","2147483648","\"3\"","null"})
    void rejectsInvalidCount(String count) { assertThatThrownBy(()->WorkoutSnapshotRequests.save(body("\"1\"",count))).isInstanceOf(WorkoutSessionFailure.class); }
    @Test void validatesStrictShapeDuplicatePropertiesAndTrailingInput() {
        for(String raw:List.of(body("\"1\"","3")+"{}",body("\"1\"","3").replace("\"dataGeneration\":\"0\"","\"dataGeneration\":\"0\",\"dataGeneration\":\"0\""),body("\"1\"","3").replace("\"approvalRequests\":[]","\"approvalRequests\":[],\"outbox\":[]"))) {
            assertThatThrownBy(()->WorkoutSnapshotRequests.save(raw)).isInstanceOf(WorkoutSessionFailure.class);
        }
    }
    @Test void preservesExactLongCounterAndCanonicalizesPropertyOrder() {
        var parsed=WorkoutSnapshotRequests.save(body("\"9223372036854775807\"","3"));
        assertThat(parsed.localRevision()).isEqualTo(Long.MAX_VALUE);
        var object=WorkoutSnapshotData.JSON.readTree(body("\"1\"","3"));
        var names=new ArrayList<>(object.propertyNames());Collections.reverse(names);
        var reordered=new LinkedHashMap<String,tools.jackson.databind.JsonNode>();
        names.forEach(name->reordered.put(name,object.get(name)));
        String differentOrder=WorkoutSnapshotData.JSON.writeValueAsString(reordered);
        assertThat(differentOrder).isNotEqualTo(object.toString());
        assertThat(WorkoutSnapshotRequests.save(differentOrder)).isEqualTo(WorkoutSnapshotRequests.save(body("\"1\"","3")));
    }
    @Test void finishReusesExactSnapshotShapeAndRequiresOriginalClientEndTime() {
        var object=WorkoutSnapshotData.JSON.readTree(body("\"1\"","3"));
        var values=new LinkedHashMap<String,tools.jackson.databind.JsonNode>();object.propertyNames().forEach(name->values.put(name,object.get(name)));
        values.put("endedAt",WorkoutSnapshotData.JSON.valueToTree("2026-10-31T16:10:00.123Z"));
        var parsed=WorkoutSnapshotRequests.finish(WorkoutSnapshotData.JSON.writeValueAsString(values));
        assertThat(parsed.state()).isEqualTo(WorkoutSnapshotRequests.save(body("\"1\"","3")));
        assertThat(parsed.endedAt()).isEqualTo(java.time.Instant.parse("2026-10-31T16:10:00.123Z"));
        assertThatThrownBy(()->WorkoutSnapshotRequests.finish(body("\"1\"","3"))).isInstanceOf(WorkoutSessionFailure.class);
        values.put("endedAt",WorkoutSnapshotData.JSON.valueToTree("2026-10-31T16:10:00.1234Z"));
        assertThatThrownBy(()->WorkoutSnapshotRequests.finish(WorkoutSnapshotData.JSON.writeValueAsString(values))).isInstanceOf(WorkoutSessionFailure.class);
    }
}
