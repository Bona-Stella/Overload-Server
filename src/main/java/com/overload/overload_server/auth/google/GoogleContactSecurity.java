package com.overload.overload_server.auth.google;

import com.overload.overload_server.security.SessionReauthentication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.stereotype.Component;

/** Proves the existing Google subject and obtains a signed verified email candidate; never claims fresh Google authentication. */
@Component
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="GOOGLE_OIDC_ENABLED",havingValue="true")
final class GoogleContactSecurity extends GoogleBoundOidcSecurity {
	static final String START="/oauth2/contact/google";
	static final String CALLBACK="/login/oauth2/code/google-contact";
	static final String FAILURE="/auth/google/contact/failure";
	GoogleContactSecurity(ClientRegistrationRepository clients,GoogleContactRequests pending,SessionReauthentication sessions){super(clients,pending,sessions,START,CALLBACK,FAILURE,"google-contact");}
	@Override protected String successPath(){return "/api/v1/auth/reauth/email/contact";}
	@Override protected void customize(OAuth2AuthorizationRequest.Builder builder){/* Standard openid/email scope; no auth_time or max_age claim. */}
	@Override protected JwtDecoderFactory<ClientRegistration> decoders(){
		var factory=new OidcIdTokenDecoderFactory();
		factory.setClaimTypeConverterFactory(client->claims->{
			if(!(claims.get("email") instanceof String)||!Boolean.TRUE.equals(claims.get("email_verified"))){throw new OAuth2AuthenticationException(new OAuth2Error("invalid_verified_email"));}
			return OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter().convert(claims);
		});
		return factory;
	}
}
