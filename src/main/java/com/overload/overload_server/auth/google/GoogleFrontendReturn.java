package com.overload.overload_server.auth.google;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Operator-owned destination only; never derived from request parameters, Host or forwarded headers. */
@Component
@ConditionalOnProperty(name = "GOOGLE_OIDC_ENABLED", havingValue = "true")
final class GoogleFrontendReturn {
    static final String LOCAL = "http://localhost:3000/auth/complete";
    private final String success;
    private final String failure;
    private final String contactSuccess;
    private final String contactFailure;

    GoogleFrontendReturn(Environment environment) {
        String[] login = destination(environment, "AUTH_FRONTEND_RETURN_URI", "/auth/complete", "/auth/failed",
                "/api/v1/auth/session", "/auth/google/failure");
        success = login[0]; failure = login[1];
        String[] contact = destination(environment, "AUTH_CONTACT_FRONTEND_RETURN_URI", "/account/contact/complete", "/account/contact/failed",
                "/api/v1/auth/reauth/email/contact", "/auth/google/contact/failure");
        contactSuccess = contact[0]; contactFailure = contact[1];
    }

    private static String[] destination(Environment environment, String property, String path, String failed,
            String defaultSuccess, String defaultFailure) {
        String configured = environment.getProperty(property, "");
        if (configured.isEmpty()) {
            return new String[] { defaultSuccess, defaultFailure };
        }
        boolean local = environment.acceptsProfiles(Profiles.of("local"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        try {
            URI uri = URI.create(configured);
            if (!configured.equals(configured.trim()) || configured.codePoints().anyMatch(Character::isISOControl)
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !path.equals(uri.getRawPath())
                    || (local ? !("http://localhost:3000" + path).equals(configured) : !"https".equals(uri.getScheme())
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))) {
                throw new IllegalArgumentException();
            }
            return new String[] { uri.toASCIIString(), uri.resolve(failed).toASCIIString() };
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid frontend return configuration; use the documented fixed routes");
        }
    }
    String success() { return success; }
    String failure() { return failure; }
    String contactSuccess() { return contactSuccess; }
    String contactFailure() { return contactFailure; }
}
