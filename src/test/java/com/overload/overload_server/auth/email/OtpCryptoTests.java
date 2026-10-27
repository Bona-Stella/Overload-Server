package com.overload.overload_server.auth.email;

import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OtpCryptoTests {

	static String randomKey() {
		var bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	@Test
	void sixDigitsPreserveLeadingZeroAndUseTheWholeUniformBound() {
		var crypto = new OtpCrypto(randomKey(), new SecureRandom() {
			@Override public int nextInt(int bound) {
				assertThat(bound).isEqualTo(1_000_000); return 7;
			}
		});
		var code = crypto.newCode();
		assertThat(code.length()).isEqualTo(6);
		assertThat(code.startsWith("00000")).isTrue();
	}

	@Test
	void verifierBindsEmailPurposeAndIssuanceAndInvalidatesOnKeyRotation() {
		var crypto = new OtpCrypto(randomKey());
		var id = UUID.randomUUID(); var code = crypto.newCode();
		var verifier = crypto.verifier("a@example.com", id, code);
		assertThat(verifier.length).isEqualTo(32);
		assertThat(MessageDigest.isEqual(verifier, crypto.verifier("a@example.com", id, code))).isTrue();
		assertThat(MessageDigest.isEqual(verifier, crypto.verifier("b@example.com", id, code))).isFalse();
		assertThat(MessageDigest.isEqual(verifier, crypto.verifier("a@example.com", UUID.randomUUID(), code))).isFalse();
		assertThat(MessageDigest.isEqual(verifier, new OtpCrypto(randomKey()).verifier("a@example.com", id, code))).isFalse();
		assertThat(new OtpEmailSender.Message(id, "a@example.com", code).toString().contains(code)).isFalse();
	}

	@Test
	void absentWeakOrInvalidKeyNeverFallsBackToPlainHashing() {
		for (var key : new String[]{"", "invalid", Base64.getEncoder().encodeToString(new byte[8])}) {
			var crypto = new OtpCrypto(key);
			assertThat(crypto.ready()).isFalse();
			assertThatThrownBy(() -> crypto.verifier("a@example.com", UUID.randomUUID(), "unused"))
					.isInstanceOf(OtpFailure.class).hasMessage("OTP_UNAVAILABLE");
		}
	}

}
