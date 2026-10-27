package com.overload.overload_server.account;

import java.util.Locale;
import java.util.Objects;

/**
 * Internal input from a trusted authentication verifier, never an HTTP request DTO.
 * Construction validates identity shape; it does not verify a Google token or email ownership.
 */
public record VerifiedIdentity(Provider provider, String issuer, String subject) {

	public static final String GOOGLE_ISSUER = "https://accounts.google.com";
	public static final String EMAIL_ISSUER = "overload:email";

	public VerifiedIdentity {
		Objects.requireNonNull(provider, "provider");
		requireToken(issuer);
		requireToken(subject);
		if (provider == Provider.GOOGLE) {
			if (!GOOGLE_ISSUER.equals(issuer) && !"accounts.google.com".equals(issuer)) {
				throw new IllegalArgumentException("Unsupported Google issuer");
			}
			issuer = GOOGLE_ISSUER;
		} else {
			if (!EMAIL_ISSUER.equals(issuer)) {
				throw new IllegalArgumentException("Unsupported email issuer");
			}
			int separator = subject.indexOf('@');
			if (separator < 1 || separator != subject.lastIndexOf('@') || separator == subject.length() - 1) {
				throw new IllegalArgumentException("Expected a verified email address");
			}
			// Preserve the local part, including case, dots and plus tags. Only the domain is folded.
			subject = subject.substring(0, separator + 1)
					+ subject.substring(separator + 1).toLowerCase(Locale.ROOT);
		}
	}

	public static VerifiedIdentity google(String issuer, String subject) {
		return new VerifiedIdentity(Provider.GOOGLE, issuer, subject);
	}

	public static VerifiedIdentity email(String verifiedAddress) {
		return new VerifiedIdentity(Provider.EMAIL, EMAIL_ISSUER, verifiedAddress);
	}

	@Override
	public String toString() {
		return "VerifiedIdentity[provider=" + provider + ", credential=redacted]";
	}

	private static void requireToken(String value) {
		if (value == null || value.isBlank()
				|| value.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
			throw new IllegalArgumentException("Identity fields must be nonblank and contain no whitespace or controls");
		}
	}

	public enum Provider {
		GOOGLE("google"), EMAIL("email");

		private final String databaseValue;

		Provider(String databaseValue) {
			this.databaseValue = databaseValue;
		}

		String databaseValue() {
			return databaseValue;
		}
	}

}
