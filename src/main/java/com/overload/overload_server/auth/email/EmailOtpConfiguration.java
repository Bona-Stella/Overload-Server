package com.overload.overload_server.auth.email;

import com.overload.overload_server.account.AccountProvisioningService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EmailOtpConfiguration {

	@Bean(destroyMethod = "close")
	HttpClient resendHttpClient() {
		return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
	}

	@Bean
	@ConditionalOnMissingBean(OtpEmailSender.class)
	OtpEmailSender resendOtpEmailSender(@Value("${OTP_EMAIL_ENABLED:false}") String enabled,
			@Value("${RESEND_API_KEY:}") String apiKey, @Value("${RESEND_FROM_EMAIL:}") String from,
			HttpClient resendHttpClient) {
		return new ResendOtpEmailSender(Boolean.parseBoolean(enabled), apiKey, from, resendHttpClient);
	}

	@Bean
	ReauthenticationEmails reauthenticationEmails(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
		return new ReauthenticationEmails(jdbc,manager,clock);
	}

	@Bean
	EmailOtpService emailOtpService(JdbcTemplate jdbc, PlatformTransactionManager manager,
			@Value("${OTP_HMAC_KEY:}") String key, OtpEmailSender sender,
			AccountProvisioningService accounts, Clock clock) {
		return new EmailOtpService(jdbc, manager, new OtpCrypto(key), sender, accounts, clock);
	}

}
