package com.overload.overload_server.auth.google;

import com.overload.overload_server.security.SessionPrincipal;
import com.overload.overload_server.security.SessionReauthentication;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.authentication.OidcAuthorizationCodeAuthenticationProvider;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.*;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

abstract class GoogleBoundOidcSecurity {
	protected final String START;
	protected final String CALLBACK;
	protected final String FAILURE;
	private final String registrationId;
	static final String SNAPSHOT = GoogleBoundOidcSecurity.class.getName() + ".snapshot";
	private static final String ORIGINAL_CONTEXT = GoogleBoundOidcSecurity.class.getName() + ".context";
	private final ClientRegistrationRepository clients;
	private final GoogleBoundRequests pending;
	private final SessionReauthentication sessions;

	GoogleBoundOidcSecurity(ClientRegistrationRepository clients, GoogleBoundRequests pending, SessionReauthentication sessions, String start, String callback, String failure, String registrationId) {
		this.clients = clients; this.pending = pending; this.sessions = sessions;
		this.START=start;this.CALLBACK=callback;this.FAILURE=failure;this.registrationId=registrationId;
	}

	protected abstract org.springframework.security.oauth2.jwt.JwtDecoderFactory<ClientRegistration> decoders();
	protected abstract void customize(OAuth2AuthorizationRequest.Builder builder);
	protected String successPath(){return "/api/v1/auth/session";}
	protected boolean handleFailure(HttpServletResponse response,RuntimeException failure)throws IOException{return false;}
	protected UUID selectedIdentity(HttpServletRequest request){
		if(request.getParameterMap().keySet().stream().anyMatch(key->!key.equals("identityId")&&!key.equals("_csrf"))){throw new IllegalArgumentException("Unexpected field");}
		try{return UUID.fromString(request.getParameter("identityId"));}catch(IllegalArgumentException|NullPointerException invalid){throw new GoogleBoundRequests.IdentityNotFound();}
	}

	void configure(HttpSecurity http, OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> tokens, OidcUserService users) {
		ClientRegistrationRepository reauthClients = id -> {
			var login = clients.findByRegistrationId("google");
			return !registrationId.equals(id) || login == null ? null : ClientRegistration.withClientRegistration(login)
					.registrationId(registrationId).redirectUri(login.getRedirectUri().substring(0,login.getRedirectUri().lastIndexOf("/login/oauth2/code/google")) + CALLBACK).build();
		};
		var delegate = new DefaultOAuth2AuthorizationRequestResolver(reauthClients, "/oauth2/reauth");
		delegate.setAuthorizationRequestCustomizer(builder -> {
			OAuth2AuthorizationRequestCustomizers.withPkce().accept(builder);
			customize(builder);
		});
		var resolver = new OAuth2AuthorizationRequestResolver() {
			@Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
				return exact(request, "POST", START) ? delegate.resolve(request, registrationId) : null;
			}
			@Override public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String id) { return resolve(request); }
		};
		var redirect = new ReauthenticationRedirectFilter(resolver,registrationId);
		redirect.setAuthorizationRequestRepository(pending);
		redirect.setRequestCache(new NullRequestCache());
		redirect.setAuthenticationFailureHandler((request, response, failure) -> fail(request, response,failure));
		var provider = new OidcAuthorizationCodeAuthenticationProvider(grant -> {
			try { return tokens.getTokenResponse(grant); }
			catch (RuntimeException failure) { throw new OAuth2AuthorizationException(new OAuth2Error("google_reauthentication_failed")); }
		}, users);
		provider.setJwtDecoderFactory(decoders());
		var callback = new ReauthenticationCallbackFilter(reauthClients, CALLBACK);
		callback.setAuthenticationManager(new ProviderManager(provider));
		callback.setAuthorizationRequestRepository(pending);
		callback.setSecurityContextRepository(new NullSecurityContextRepository());
		callback.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
		callback.setAllowSessionCreation(false);
		callback.setRequiresAuthenticationRequestMatcher(request -> exact(request, "GET", CALLBACK));
		callback.setAuthenticationSuccessHandler((request, response, authentication) -> {
			try {
				restore(request);
				if (!(authentication instanceof OAuth2AuthenticationToken token) || !registrationId.equals(token.getAuthorizedClientRegistrationId())
						|| !(token.getPrincipal() instanceof OidcUser user)) { throw new SessionReauthentication.SessionChanged(); }
				pending.verified(request, response, user);
				redirect(request, response, successPath());
			} catch (RuntimeException failure) { fail(request, response,failure); }
		});
		callback.setAuthenticationFailureHandler((request, response, failure) -> fail(request, response,failure));
		http.addFilterBefore(redirect, OAuth2AuthorizationRequestRedirectFilter.class);
		http.addFilterBefore(new Boundary(), ReauthenticationRedirectFilter.class);
		http.addFilterBefore(callback, OAuth2LoginAuthenticationFilter.class);
	}

	private static final class ReauthenticationRedirectFilter extends OAuth2AuthorizationRequestRedirectFilter {
		private final String purpose;
		ReauthenticationRedirectFilter(OAuth2AuthorizationRequestResolver resolver,String purpose) { super(resolver);this.purpose=purpose; }
		@Override protected String getAlreadyFilteredAttributeName(){return getClass().getName()+"."+purpose+".FILTERED";}
	}
	private static final class ReauthenticationCallbackFilter extends OAuth2LoginAuthenticationFilter {
		ReauthenticationCallbackFilter(ClientRegistrationRepository clients,String callback) { super(clients, new DiscardingAuthorizedClients(), callback); }
	}

	private final class Boundary extends OncePerRequestFilter {
		@Override protected boolean shouldNotFilter(HttpServletRequest request) {
			return !exact(request, "POST", START) && !exact(request, "GET", CALLBACK);
		}
		@Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
			var original = SecurityContextHolder.getContext().getAuthentication();
			if (original == null || !original.isAuthenticated() || !(original.getPrincipal() instanceof SessionPrincipal principal)) {
				error(response, 401, "AUTHENTICATION_REQUIRED"); return;
			}
			request.setAttribute(ORIGINAL_CONTEXT, new SecurityContextImpl(original));
			try {
				var snapshot = sessions.capture(request, principal.userId()); request.setAttribute(SNAPSHOT, snapshot);
				if (exact(request, "POST", START)) {
					UUID identity;
					try {identity=selectedIdentity(request);}catch(GoogleBoundRequests.IdentityNotFound missing){throw missing;}catch(IllegalArgumentException invalid){error(response,400,"INVALID_REQUEST");return;}
					pending.begin(request, snapshot, identity);
				}
				chain.doFilter(request, response);
			} catch (GoogleBoundRequests.IdentityNotFound missing) {
				restore(request); error(response, 404, "IDENTITY_NOT_FOUND");
			} catch (RuntimeException failure) { fail(request, response,failure); }
		}
	}
	private static boolean exact(HttpServletRequest request, String method, String path) {
		return method.equals(request.getMethod()) && (request.getContextPath() + path).equals(request.getRequestURI());
	}
	private static void restore(HttpServletRequest request) {
		if (request.getAttribute(ORIGINAL_CONTEXT) instanceof SecurityContext original) { SecurityContextHolder.setContext(original); }
		else { SecurityContextHolder.clearContext(); }
	}
	private void fail(HttpServletRequest request, HttpServletResponse response,RuntimeException failure)throws IOException {
		restore(request);if(!handleFailure(response,failure)){redirect(request,response,FAILURE);}
	}
	private static void redirect(HttpServletRequest request, HttpServletResponse response, String path) {
		response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
		response.setStatus(302); response.setHeader("Location", request.getContextPath() + path);
	}
	protected static void error(HttpServletResponse response, int status, String code) throws IOException {
		response.setStatus(status); response.setContentType("application/json"); response.getWriter().write("{\"code\":\"" + code + "\"}");
	}
}
