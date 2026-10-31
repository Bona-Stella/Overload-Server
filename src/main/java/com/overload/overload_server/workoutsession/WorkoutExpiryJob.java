package com.overload.overload_server.workoutsession;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name="overload.workout.expiry.enabled",havingValue="true",matchIfMissing=true)
class WorkoutExpiryJob {
    private final WorkoutSessionService sessions;
    WorkoutExpiryJob(WorkoutSessionService sessions) { this.sessions=sessions; }

    @Scheduled(fixedDelayString="${overload.workout.expiry.delay-ms:60000}")
    void closeExpired() { sessions.expireBatch(); }
}
