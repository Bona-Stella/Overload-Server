package com.overload.overload_server.auth.google;

import com.overload.overload_server.security.SessionReauthentication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.stereotype.Component;

/** Strict Google auth_time validation is preserved but is not the common email registration flow. */
@Component
@ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name={"GOOGLE_OIDC_ENABLED","GOOGLE_REAUTH_ENABLED"},havingValue="true")
final class GoogleReauthenticationSecurity extends GoogleBoundOidcSecurity {
	static final String START="/oauth2/reauth/google";
	static final String CALLBACK="/login/oauth2/code/google-reauth";
	static final String FAILURE="/auth/google/reauth/failure";
	GoogleReauthenticationSecurity(ClientRegistrationRepository clients,GoogleReauthenticationRequests pending,SessionReauthentication sessions){
		super(clients,pending,sessions,START,CALLBACK,FAILURE,"google-reauth");
	}
	@Override protected org.springframework.security.oauth2.jwt.JwtDecoderFactory<ClientRegistration> decoders(){return GoogleAuthenticationTime.decoders();}
	@Override protected void customize(OAuth2AuthorizationRequest.Builder builder){builder.additionalParameters(parameters->{
		parameters.put("claims","{\"id_token\":{\"auth_time\":{\"essential\":true}}}");
		parameters.put("max_age","0");
	});}
}
