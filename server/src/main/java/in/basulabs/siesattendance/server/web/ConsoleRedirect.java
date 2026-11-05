package in.basulabs.siesattendance.server.web;

import androidx.annotation.NonNull;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The server's own address, typed into a browser, leads to the console.
 * <p>
 * A redirect rather than serving the page at {@code /}: the page's address is the one people
 * bookmark and the one the 404 page links to, so there is one of it. A 302, not a 301, because a
 * browser keeps a permanent redirect for as long as it likes, and {@code /} may one day be
 * something else.
 * <p>
 * Outside {@code /api/*}, so the token filter never sees it — like the page itself, it holds
 * nothing.
 */
@Configuration
public class ConsoleRedirect implements WebMvcConfigurer {

	@Override
	public void addViewControllers(@NonNull ViewControllerRegistry registry) {
		registry.addRedirectViewController("/", "/console.html");
	}
}
