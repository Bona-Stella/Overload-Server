package com.overload.overload_server.workout;

import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.sql.SQLException;

@RestControllerAdvice(assignableTypes = WorkoutWriteController.class)
public class WorkoutWriteErrors {

	@ExceptionHandler(WorkoutWriteFailure.class)
	ResponseEntity<WorkoutReadErrors.Body> failure(WorkoutWriteFailure failure) {
		return response(failure.reason.status, failure.reason.name());
	}

	@ExceptionHandler({HttpMessageNotReadableException.class, ServletRequestBindingException.class,
			MethodArgumentTypeMismatchException.class})
	ResponseEntity<WorkoutReadErrors.Body> invalid() { return response(400, "INVALID_REQUEST"); }

	@ExceptionHandler(Exception.class)
	ResponseEntity<WorkoutReadErrors.Body> failed(Exception failure) throws Exception {
		if (failure instanceof AuthenticationException || failure instanceof AccessDeniedException || failure instanceof ErrorResponse) throw failure;
		for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
			if (cause instanceof OptimisticLockingFailureException || cause instanceof jakarta.persistence.OptimisticLockException
					|| cause instanceof SQLException sql && ("40001".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
				return response(409, "WRITE_CONFLICT");
			}
			if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) return response(409, "EXERCISE_ID_CONFLICT");
		}
		return WorkoutReadErrors.unavailable(failure) ? response(503, "WORKOUT_DATA_UNAVAILABLE")
				: response(500, "WORKOUT_DATA_WRITE_FAILED");
	}

	private static ResponseEntity<WorkoutReadErrors.Body> response(int status, String code) {
		if (status >= 500) LoggerFactory.getLogger(WorkoutWriteErrors.class).warn("Workout write failed code={}", code);
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(new WorkoutReadErrors.Body(code));
	}
}
