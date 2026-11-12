package in.basulabs.siesattendance.server.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * The second lock: a controller reached without an actor refuses by itself.
 * <p>
 * {@link BearerTokenFilter} is the first one, and while it holds this can never be seen from
 * outside — which is exactly what a backstop is, and why it is tested here rather than over
 * HTTP. It is not theoretical: an encoded path once walked past the filter, and every
 * controller but {@link ImportFormatsController} answered 401 from {@link RequestActor#of}
 * while that one answered 200 with its body. The filter refuses by default now; this is what
 * happens if a later one ever lets something through again.
 */
class ActorRequiredTest {

	@Test
	void aRequestThatCarriesNoVerifiedSessionHasNoActor() {

		ApiException refused = assertThrows(ApiException.class,
				() -> RequestActor.of(new MockHttpServletRequest()));

		assertEquals(401, refused.status());
		assertEquals("unauthenticated", refused.code());
	}

	@Test
	void theCsvFormatsAskForTheActorTheyDoNotUse() {

		// The endpoint has nothing worth protecting -- the README prints the same text -- and
		// it asks anyway, so that no endpoint in this service can answer as nobody.
		ApiException refused = assertThrows(ApiException.class,
				() -> new ImportFormatsController().formats(new MockHttpServletRequest()));

		assertEquals(401, refused.status());
	}
}
