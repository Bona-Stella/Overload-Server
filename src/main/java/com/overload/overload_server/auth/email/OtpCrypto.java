package com.overload.overload_server.auth.email;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

final class OtpCrypto {

	private final byte[] key;
	private final SecureRandom random;

	OtpCrypto(String base64Key) {
		this(base64Key, new SecureRandom());
	}

	OtpCrypto(String base64Key, SecureRandom random) {
		byte[] decoded;
		try {
			decoded = Base64.getDecoder().decode(base64Key);
		} catch (IllegalArgumentException exception) {
			decoded = new byte[0];
		}
		this.key = decoded;
		this.random = random;
	}

	boolean ready() {
		return key.length >= 32;
	}

	String newCode() {
		return String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
	}

	byte[] verifier(String email, UUID issuance, String code) {
		if (!ready()) {
			throw new OtpFailure(503, "OTP_UNAVAILABLE");
		}
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			for (var field : new String[]{"overload-otp-v1", "LOGIN", email, issuance.toString(), code}) {
				var bytes = field.getBytes(StandardCharsets.UTF_8);
				mac.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
				mac.update(bytes);
			}
			return mac.doFinal();
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("HMAC unavailable");
		}
	}

	static String identifier(String scope, String value) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			digest.update(scope.getBytes(StandardCharsets.UTF_8));
			digest.update((byte) 0);
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException exception) {
			throw new IllegalStateException("Digest unavailable");
		}
	}

}
