package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import in.basulabs.siesattendance.backend.sync.ServerAddress;

/**
 * What the server address field accepts, and what it stores.
 * <p>
 * The dialog needs a phone, and so does the cleartext half of the check, which asks the
 * platform's network security policy. What the text alone decides is a pure function and is
 * held to it here: an address stored in a different shape from the one typed is an address that
 * fails in {@code SyncClient}, far from the field where it could have been corrected.
 */
public class ServerAddressTest {

	@Test
	public void aFullAddressIsKeptAsItIs() {
		assertEquals("https://attendance.example.edu/api/v1",
				ServerAddress.normalise("https://attendance.example.edu/api/v1"));
	}

	@Test
	public void aBareHostGetsHttpsAndTheApiPath() {

		// What an administrator reads out and what a professor types.
		assertEquals("https://attendance.example.edu" + ServerAddress.API_PATH,
				ServerAddress.normalise("attendance.example.edu"));

		assertEquals("https://attendance.example.edu" + ServerAddress.API_PATH,
				ServerAddress.normalise("https://attendance.example.edu/"));
	}

	@Test
	public void spacesCaseAndTrailingSlashesAreTidied() {
		assertEquals("https://attendance.example.edu/api/v1",
				ServerAddress.normalise("  HTTPS://Attendance.Example.EDU/api/v1//  "));
	}

	@Test
	public void aPortAndAPathOfItsOwnSurvive() {

		assertEquals("http://192.168.1.20:8080/api/v1",
				ServerAddress.normalise("http://192.168.1.20:8080/api/v1"));

		// Behind a proxy that serves the API somewhere else. Not second-guessed.
		assertEquals("https://example.edu/sies/api/v1",
				ServerAddress.normalise("https://example.edu/sies/api/v1"));
	}

	@Test
	public void goodAddressesHaveNothingWrongWithThem() {
		assertNull(ServerAddress.check("attendance.example.edu"));
		assertNull(ServerAddress.check("http://10.0.2.2:8080/api/v1"));
	}

	@Test
	public void onlyHttpAndHttpsAreAccepted() {
		assertEquals(ServerAddress.Problem.NOT_HTTP,
				ServerAddress.check("ftp://attendance.example.edu"));
	}

	@Test
	public void somethingThatIsNotAnAddressIsSaidToBeOne() {

		assertEquals(ServerAddress.Problem.NOT_AN_ADDRESS,
				ServerAddress.check("attendance example edu"));

		assertEquals(ServerAddress.Problem.NOT_AN_ADDRESS, ServerAddress.check("https://"));
	}

	@Test
	public void queriesFragmentsAndUserNamesAreRefusedRatherThanDropped() {

		assertEquals(ServerAddress.Problem.NOT_AN_ADDRESS,
				ServerAddress.check("https://example.edu/api/v1?x=1"));

		assertEquals(ServerAddress.Problem.NOT_AN_ADDRESS,
				ServerAddress.check("https://example.edu/api/v1#top"));

		assertEquals(ServerAddress.Problem.NOT_AN_ADDRESS,
				ServerAddress.check("https://admin@example.edu/api/v1"));
	}

	@Test
	public void normalisingSomethingWithAProblemFailsLoudly() {
		assertThrows(IllegalArgumentException.class,
				() -> ServerAddress.normalise("ftp://example.edu"));
	}
}
