package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import javax.sql.DataSource;

import in.basulabs.siesattendance.server.security.AccountDirectory;
import in.basulabs.siesattendance.server.security.DeviceRegistry;
import in.basulabs.siesattendance.server.security.Sessions;

/**
 * What the service is made of.
 * <p>
 * Plain constructor calls rather than annotations on the classes themselves, so that
 * {@code security} and {@code importer} stay free of Spring: they are used by command-line tools
 * and by tests that boot nothing, and a class annotated into a container is a class that needs
 * one.
 * <p>
 * All of them take the same {@link DataSource}, which connects as {@code sies_app} — not the
 * owner, not a superuser, no {@code BYPASSRLS}. That is what makes the policies apply to this
 * service at all.
 */
@Configuration
public class WebBeans {

	@Bean
	@NonNull
	public AccountDirectory accountDirectory(@NonNull DataSource dataSource) {
		return new AccountDirectory(dataSource);
	}

	@Bean
	@NonNull
	public Sessions sessions(@NonNull DataSource dataSource) {
		return new Sessions(dataSource);
	}

	@Bean
	@NonNull
	public DeviceRegistry deviceRegistry(@NonNull DataSource dataSource) {
		return new DeviceRegistry(dataSource);
	}

	@Bean
	@NonNull
	public ActorDatabase actorDatabase(@NonNull DataSource dataSource) {
		return new ActorDatabase(dataSource);
	}

	/**
	 * @param allowance how many sign-in attempts one address gets per window. Configurable so a
	 *                  test can prove the limit works without waiting out the window; there is
	 *                  no reason to lower it in production and every reason not to raise it far
	 */
	@Bean
	@NonNull
	public LoginRateLimiter loginRateLimiter(
			@Value("${sies.login.attempts-per-window:#{null}}") @Nullable Integer allowance) {

		return allowance == null ? new LoginRateLimiter()
				: new LoginRateLimiter(allowance, LoginRateLimiter.DEFAULT_WINDOW);
	}

	/**
	 * The token filter, registered for the API and nothing else.
	 * <p>
	 * A {@code FilterRegistrationBean} rather than letting Boot register the filter bean for
	 * every path: the health probe has to answer before anybody has signed in, and a filter that
	 * runs on it is a filter one careless edit away from refusing it. First in the chain,
	 * because nothing that follows should run for a request whose token is not good.
	 */
	@Bean
	@NonNull
	public FilterRegistrationBean<BearerTokenFilter> bearerTokenFilter(
			@NonNull Sessions sessions) {

		FilterRegistrationBean<BearerTokenFilter> registration =
				new FilterRegistrationBean<>(new BearerTokenFilter(sessions));

		registration.addUrlPatterns("/api/*");
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}
}
