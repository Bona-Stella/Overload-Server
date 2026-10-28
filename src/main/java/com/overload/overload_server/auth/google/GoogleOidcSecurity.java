package com.overload.overload_server.auth.google;

import com.overload.overload_server.account.AccountProvisioningService;
import com.overload.overload_server.account.VerifiedIdentity;
import com.overload.overload_server.security.VerifiedSessionLogin;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;

@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "GOOGLE_OIDC_ENABLED", havingValue = "true")
public class GoogleOidcSecurity {

	static final String START = "/oauth2/authorization/google";
	static final String CALLBACK = "/login/oauth2/code/google";
	static final String FAILURE = "/auth/google/failure";
	private final ClientRegistrationRepository clients;
	private final JdbcAuthorizationRequestRepository pending;
	private final AccountProvisioningService accounts;
	private final VerifiedSessionLogin sessions;
	private final HttpClient tokenHttpClient;
	private final org.springframework.beans.factory.ObjectProvider<GoogleReauthenticationSecurity> reauthentication;
	private final GoogleContactSecurity contact;
	private final GoogleLinkSecurity linking;

	public GoogleOidcSecurity(ClientRegistrationRepository clients, JdbcTemplate jdbc, PlatformTransactionManager manager,
			SessionRepository<? extends Session> repository, AccountProvisioningService accounts, VerifiedSessionLogin sessions,
			@Qualifier("googleTokenHttpClient") HttpClient tokenHttpClient, org.springframework.beans.factory.ObjectProvider<GoogleReauthenticationSecurity> reauthentication, GoogleContactSecurity contact,GoogleLinkSecurity linking) {
		this.clients = clients;
		this.pending = new JdbcAuthorizationRequestRepository(jdbc, manager, repository);
		this.accounts = accounts;
		this.sessions = sessions;
		this.tokenHttpClient = tokenHttpClient;
		this.reauthentication = reauthentication;
		this.contact=contact;
		this.linking=linking;
	}

	public void configure(HttpSecurity http) throws Exception {
		var resolver = new DefaultOAuth2AuthorizationRequestResolver(clients, "/oauth2/authorization");
		resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce());
		var exactResolver = new OAuth2AuthorizationRequestResolver() {
			@Override
			public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
				return exactGet(request, START) ? resolver.resolve(request) : null;
			}
			@Override
			public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String registrationId) {
				return exactGet(request, START) && "google".equals(registrationId) ? resolver.resolve(request, registrationId) : null;
			}
		};
		var tokenClient = new RestClientAuthorizationCodeTokenResponseClient();
		var requestFactory = new JdkClientHttpRequestFactory(tokenHttpClient);
		requestFactory.setReadTimeout(Duration.ofSeconds(5));
		tokenClient.setRestClient(RestClient.builder().requestFactory(requestFactory)
				.configureMessageConverters(converters -> converters.disableDefaults()
						.addCustomConverter(new FormHttpMessageConverter())
						.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
				.defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
		var users = new OidcUserService();
		// The ID token is sufficient for issuer+sub. No Google API/userinfo or offline access is needed.
		users.setRetrieveUserInfo(request -> false);
		http.oauth2Login(oauth -> oauth.clientRegistrationRepository(clients)
				.loginPage(START)
				.authorizedClientRepository(new DiscardingAuthorizedClients())
				.authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(exactResolver).authorizationRequestRepository(pending))
				.loginProcessingUrl(CALLBACK)
				.tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(request -> {
					try {
						return tokenClient.getTokenResponse(request);
					} catch (RuntimeException exception) {
						throw new OAuth2AuthorizationException(new OAuth2Error("google_exchange_failed"));
					}
				}))
				.userInfoEndpoint(endpoint -> endpoint.oidcUserService(users))
				.successHandler((request, response, authentication) -> {
					try {
						// Only the configured OIDC provider can reach this handler; no public OidcUser/token input API exists.
						if (!(authentication instanceof OAuth2AuthenticationToken oauthToken)
								|| !"google".equals(oauthToken.getAuthorizedClientRegistrationId())
								|| !(oauthToken.getPrincipal() instanceof OidcUser user)
								|| TransactionSynchronizationManager.isActualTransactionActive()) {
							throw new IllegalStateException();
						}
						var identity = VerifiedIdentity.google(user.getIdToken().getIssuer().toString(), user.getIdToken().getSubject());
						SecurityContextHolder.clearContext();
						var account = accounts.provision(identity, null);
						sessions.establish(request, response, account.id());
						redirect(request, response, "/api/v1/auth/session");
					} catch (RuntimeException exception) {
						fail(request, response);
					}
				})
				.failureHandler((request, response, exception) -> fail(request, response))
				.withObjectPostProcessor(new ObjectPostProcessor<OAuth2LoginAuthenticationFilter>() {
					@Override
					public <O extends OAuth2LoginAuthenticationFilter> O postProcess(O filter) {
						// The default filter saves its temporary OAuth token before invoking successHandler.
						// Only VerifiedSessionLogin may persist the final Overload principal.
						filter.setSecurityContextRepository(new NullSecurityContextRepository());
						filter.setSessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy());
						filter.setRequiresAuthenticationRequestMatcher(request -> exactGet(request, CALLBACK));
						return filter;
					}
				})
				.withObjectPostProcessor(new ObjectPostProcessor<OAuth2AuthorizationRequestRedirectFilter>() {
					@Override
					public <O extends OAuth2AuthorizationRequestRedirectFilter> O postProcess(O filter) {
						filter.setRequestCache(new NullRequestCache());
						filter.setAuthenticationFailureHandler((request, response, exception) -> fail(request, response));
						return filter;
					}
				}));
		http.addFilterBefore(new BrowserBoundary(), OAuth2AuthorizationRequestRedirectFilter.class);
		var experimental=reauthentication.getIfAvailable();
		if(experimental!=null){experimental.configure(http,tokenClient,users);}
		contact.configure(http,tokenClient,users);
		linking.configure(http,tokenClient,users);
	}

	private static boolean exactGet(HttpServletRequest request, String path) {
		return "GET".equals(request.getMethod()) && (request.getContextPath() + path).equals(request.getRequestURI());
	}

	private static void redirect(HttpServletRequest request, HttpServletResponse response, String path) {
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("Referrer-Policy", "no-referrer");
		response.setStatus(302);
		response.setHeader("Location", request.getContextPath() + path);
	}

	private static void fail(HttpServletRequest request, HttpServletResponse response) {
		SecurityContextHolder.clearContext();
		try {
			if (request.getSession(false) != null) {
				request.getSession(false).invalidate();
			}
		} catch (RuntimeException cleanupFailure) {
			// Store failure must not turn into a success redirect or leak provider/request details.
		}
		redirect(request, response, FAILURE);
	}

	private static final class BrowserBoundary extends OncePerRequestFilter {
		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return !exactGet(request, START) && !exactGet(request, CALLBACK);
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws IOException, ServletException {
			response.setHeader("Cache-Control", "no-store");
			response.setHeader("Referrer-Policy", "no-referrer");
			var current = SecurityContextHolder.getContext().getAuthentication();
			if (current != null && current.isAuthenticated() && !(current instanceof AnonymousAuthenticationToken)) {
				try {
					if (request.getSession(false) != null) {
						request.getSession(false).removeAttribute(JdbcAuthorizationRequestRepository.ATTRIBUTE);
					}
				} catch (RuntimeException cleanupFailure) {
					// Never replace or clear a valid existing user's authentication to clean an OAuth attempt.
				}
				response.setStatus(409);
				response.setContentType("application/json");
				response.getWriter().write("{\"code\":\"LOGOUT_REQUIRED\"}");
				return;
			}
			try {
				chain.doFilter(request, response);
			} catch (RuntimeException exception) {
				fail(request, response);
			}
		}
	}

}
