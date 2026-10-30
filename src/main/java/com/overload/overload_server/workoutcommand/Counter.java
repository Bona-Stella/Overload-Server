package com.overload.overload_server.workoutcommand;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.annotation.JsonCreator;

/** Exact wire representation for generation, version, epoch and revision. */
public record Counter(@JsonValue String value) implements Comparable<Counter> {

	@JsonCreator(mode = JsonCreator.Mode.DELEGATING)
	public Counter {
		if (value == null || !value.matches("0|[1-9][0-9]{0,18}")) throw invalid();
		try { Long.parseLong(value); }
		catch (NumberFormatException failure) { throw invalid(); }
	}

	public static Counter of(long value) { return new Counter(Long.toString(value)); }
	public long longValue() { return Long.parseLong(value); }
	public Counter next() { return of(Math.addExact(longValue(), 1)); }
	@Override public int compareTo(Counter other) { return Long.compare(longValue(), other.longValue()); }
	private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid canonical Counter"); }
}
