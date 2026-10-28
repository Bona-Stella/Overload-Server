package com.overload.overload_server.auth.email;

import java.util.UUID;

public interface OtpEmailSender {

	boolean ready();

	/** Returns only after the provider unambiguously accepts this exact issuance. Not a delivery receipt. */
	void send(Message message);

	record Message(UUID issuanceId, String recipient, String code, OtpPurpose purpose) {
		public Message(UUID issuanceId, String recipient, String code) {
			this(issuanceId, recipient, code, OtpPurpose.LOGIN);
		}
		public Message { java.util.Objects.requireNonNull(purpose); }
		@Override
		public String toString() {
			return "OtpMessage[redacted]";
		}
	}

}
