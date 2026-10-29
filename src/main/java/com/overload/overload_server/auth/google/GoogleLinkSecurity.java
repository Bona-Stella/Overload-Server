package com.overload.overload_server.auth.google;

import com.overload.overload_server.auth.identity.IdentityChangeFailure;
import com.overload.overload_server.security.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.registration.*;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.UUID;

@Component @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name="GOOGLE_OIDC_ENABLED",havingValue="true")
final class GoogleLinkSecurity extends GoogleBoundOidcSecurity {
    static final String START="/oauth2/link/google",CALLBACK="/login/oauth2/code/google-link",FAILURE="/auth/google/link/failure";
    private final GoogleFrontendReturn frontend;
    GoogleLinkSecurity(ClientRegistrationRepository clients,GoogleLinkRequests pending,SessionReauthentication sessions,GoogleFrontendReturn frontend){super(clients,pending,sessions,START,CALLBACK,FAILURE,"google-link");this.frontend=frontend;}
    @Override protected String successPath(){return frontend.linkSuccess();}
    @Override protected String failurePath(){return frontend.linkFailure();}
    @Override protected void customize(OAuth2AuthorizationRequest.Builder builder){builder.additionalParameters(p->p.put("prompt","select_account"));}
    @Override protected JwtDecoderFactory<ClientRegistration> decoders(){return new OidcIdTokenDecoderFactory();}
    @Override protected UUID selectedIdentity(HttpServletRequest request){
        if(request.getParameterMap().keySet().stream().anyMatch(k->!k.equals("_csrf"))
                || (request.getContentLengthLong()>0&&(request.getContentType()==null||!request.getContentType().startsWith("application/x-www-form-urlencoded")))){
            throw new IllegalArgumentException("Unexpected input");
        }
        return null;
    }
    @Override protected boolean handleFailure(HttpServletResponse response,RuntimeException failure)throws IOException {
        // Opt-in browser return carries no reason or identity data. The original JSON contract remains the default.
        if(frontend.linkConfigured()){return false;}
        if(failure instanceof IdentityChangeFailure value){error(response,value.status,value.code);return true;}
        if(failure instanceof ReauthenticationSessionRepository.EmailProofRequired){error(response,403,"EMAIL_OWNERSHIP_PROOF_REQUIRED");return true;}
        if(failure instanceof SessionReauthentication.SessionChanged){error(response,401,"AUTHENTICATION_REQUIRED");return true;}
        if(failure instanceof org.springframework.dao.DataAccessException){error(response,503,"IDENTITY_CHANGE_UNAVAILABLE");return true;}
        return false;
    }
}
