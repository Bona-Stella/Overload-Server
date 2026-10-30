package com.overload.overload_server.workoutcommand;

/** Internal classifications only; existing HTTP advice is intentionally unchanged. */
public final class WriteContractViolation extends RuntimeException {

	public enum Code { AUTHENTICATION_REQUIRED, OWNER_CONTEXT_MISMATCH, DATA_GENERATION_MISMATCH, ID_UNAVAILABLE }
	private final Code code;
	public WriteContractViolation(Code code) { super(code.name()); this.code = code; }
	public Code code() { return code; }
}
