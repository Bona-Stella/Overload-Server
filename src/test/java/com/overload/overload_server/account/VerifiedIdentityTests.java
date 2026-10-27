package com.overload.overload_server.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VerifiedIdentityTests {

	@Test
	void canonicalizesTheTwoGoogleIssuersAndKeepsSubjectCase() {
		var identity = VerifiedIdentity.google("accounts.google.com", "GoogleSubject");
		assertThat(identity).isEqualTo(VerifiedIdentity.google("https://accounts.google.com", "GoogleSubject"));
		assertThat(identity).isNotEqualTo(VerifiedIdentity.google("https://accounts.google.com", "googlesubject"));
	}

	@Test
	void foldsOnlyEmailDomainAndNeverAliasesLocalPartsOrProviders() {
		var identity = VerifiedIdentity.email("First.Last+Tag@EXAMPLE.COM");
		assertThat(identity.subject()).isEqualTo("First.Last+Tag@example.com");
		assertThat(identity).isNotEqualTo(VerifiedIdentity.email("first.last+Tag@example.com"));
		assertThat(identity).isNotEqualTo(VerifiedIdentity.email("FirstLast+Tag@example.com"));
		assertThat(identity).isNotEqualTo(VerifiedIdentity.email("First.Last@example.com"));
		assertThat(identity).isNotEqualTo(VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER, identity.subject()));
		assertThat(identity.toString()).doesNotContain(identity.subject());
	}

	@Test
	void rejectsMalformedIdentityShapesWithoutExposingCredentials() {
		assertThatThrownBy(() -> VerifiedIdentity.google("https://untrusted.example", "subject"))
				.isInstanceOf(IllegalArgumentException.class);
		for (var subject : new String[]{"", " \t", " subject", "sub\nject"}) {
			assertThatThrownBy(() -> VerifiedIdentity.google(VerifiedIdentity.GOOGLE_ISSUER, subject))
					.isInstanceOf(IllegalArgumentException.class);
		}
		for (var address : new String[]{"no-at", "@example.com", "name@", "name@@example.com", "a b@example.com"}) {
			assertThatThrownBy(() -> VerifiedIdentity.email(address)).isInstanceOf(IllegalArgumentException.class)
					.hasMessageNotContaining(address);
		}
		assertThatThrownBy(() -> new VerifiedIdentity(VerifiedIdentity.Provider.EMAIL, "other", "a@example.com"))
				.isInstanceOf(IllegalArgumentException.class);
	}

}
