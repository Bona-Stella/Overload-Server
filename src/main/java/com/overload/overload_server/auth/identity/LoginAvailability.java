package com.overload.overload_server.auth.identity;

import com.overload.overload_server.auth.email.EmailOtpService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

@Component @ConditionalOnWebApplication(type=ConditionalOnWebApplication.Type.SERVLET)
public class LoginAvailability {
    private final boolean email,google;
    private final ObjectProvider<EmailOtpService> otp;
    public LoginAvailability(@Value("${EMAIL_LOGIN_ENABLED:true}") boolean email,@Value("${GOOGLE_OIDC_ENABLED:false}") boolean google,ObjectProvider<EmailOtpService> otp){this.email=email;this.google=google;this.otp=otp;}
    public boolean emailEnabled(){return email;}
    public boolean usable(String provider){
        if(provider.equals("google")){return google;}
        var service=otp.getIfAvailable();
        return provider.equals("email")&&email&&service!=null&&service.available();
    }
}
