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

    GoogleFrontendReturn(Environment environment) {
        String configured = environment.getProperty("AUTH_FRONTEND_RETURN_URI", "");
        if (configured.isEmpty()) {
            success = "/api/v1/auth/session";
            failure = "/auth/google/failure";
            return;
        }
        boolean local = environment.acceptsProfiles(Profiles.of("local"))
                && !environment.acceptsProfiles(Profiles.of("prod", "production"));
        try {
            URI uri = URI.create(configured);
            if (!configured.equals(configured.trim()) || configured.codePoints().anyMatch(Character::isISOControl)
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || !"/auth/complete".equals(uri.getRawPath())
                    || (local ? !LOCAL.equals(configured) : !"https".equals(uri.getScheme())
                        || (uri.getPort() != -1 && uri.getPort() != 443)
                        || "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost()))) {
                throw new IllegalArgumentException();
            }
            success = uri.toASCIIString();
            failure = uri.resolve("/auth/failed").toASCIIString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Invalid frontend return configuration; use the documented fixed routes");
        }
    }
    String success() { return success; }
    String failure() { return failure; }
}
