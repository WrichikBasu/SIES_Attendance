package in.basulabs.siesattendance.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Base64;

import in.basulabs.siesattendance.backend.sync.DeviceIdentity;
import in.basulabs.siesattendance.device.support.StagingFiles;

/**
 * The session token, sealed with a key in this phone's Android Keystore.
 * <p>
 * No JVM test can reach this: the Keystore is a system service backed, on most phones, by the
 * TEE, and AES-GCM through it behaves the way that hardware does, not the way the JDK's provider
 * does. What has to hold is what {@link DeviceIdentity}'s comments promise — the token comes back,
 * the preferences never hold it in the clear, and a blob that no longer decrypts is treated as
 * "signed out", never as a crash.
 * <p>
 * These run in the app's own process, against its real preferences file and key alias. Whatever
 * token was there before is saved first and put back afterwards, so running this on a signed-in
 * debug install does not sign it out. The key itself is reused, not replaced.
 */
@RunWith(AndroidJUnit4.class)
public class TokenKeystoreDeviceTest {

	/** {@code DeviceIdentity.PREFS}, {@code TOKEN} and {@code TOKEN_IV}. */
	private static final String PREFS = "sync_identity";

	private static final String TOKEN = "token";

	private static final String TOKEN_IV = "tokenIv";

	private static final String SAMPLE = "wbdevice.sample-token_0123456789abcdefghijklmnop";

	private Context context;

	private String savedToken;

	private String savedIv;

	@Before
	public void saveWhateverIsThere() {

		context = StagingFiles.context();
		savedToken = prefs().getString(TOKEN, null);
		savedIv = prefs().getString(TOKEN_IV, null);
	}

	@After
	public void putItBack() {

		SharedPreferences.Editor editor = prefs().edit();

		if (savedToken == null) {
			editor.remove(TOKEN);
		} else {
			editor.putString(TOKEN, savedToken);
		}
		if (savedIv == null) {
			editor.remove(TOKEN_IV);
		} else {
			editor.putString(TOKEN_IV, savedIv);
		}
		assertTrue("could not restore the app's token", editor.commit());
	}

	@Test
	public void aStoredTokenComesBack() {

		DeviceIdentity.storeToken(context, SAMPLE);

		assertTrue(DeviceIdentity.hasToken(context));
		assertEquals(SAMPLE, DeviceIdentity.token(context));
	}

	@Test
	public void thePreferencesNeverHoldTheTokenInTheClear() {

		DeviceIdentity.storeToken(context, SAMPLE);

		String sealed = prefs().getString(TOKEN, null);
		assertNotNull(sealed);
		assertFalse(sealed.contains(SAMPLE));
		assertFalse(new String(Base64.getDecoder().decode(sealed),
				java.nio.charset.StandardCharsets.ISO_8859_1).contains(SAMPLE));

		assertEquals("a 96-bit GCM nonce", 12,
				Base64.getDecoder().decode(prefs().getString(TOKEN_IV, "")).length);
	}

	@Test
	public void sealingTheSameTokenTwiceUsesAFreshNonce() {

		DeviceIdentity.storeToken(context, SAMPLE);
		String firstIv = prefs().getString(TOKEN_IV, null);
		String firstSealed = prefs().getString(TOKEN, null);

		DeviceIdentity.storeToken(context, SAMPLE);

		assertNotEquals(firstIv, prefs().getString(TOKEN_IV, null));
		assertNotEquals(firstSealed, prefs().getString(TOKEN, null));
	}

	@Test
	public void aTamperedBlobMeansSignedOutNotACrash() {

		DeviceIdentity.storeToken(context, SAMPLE);

		byte[] sealed = Base64.getDecoder().decode(prefs().getString(TOKEN, ""));
		sealed[0] ^= 0x01;
		assertTrue(prefs().edit()
				.putString(TOKEN, Base64.getEncoder().encodeToString(sealed)).commit());

		assertNull("GCM must reject it", DeviceIdentity.token(context));
		assertFalse("and the useless blob is forgotten", DeviceIdentity.hasToken(context));
	}

	@Test
	public void forgettingTheTokenForgetsIt() {

		DeviceIdentity.storeToken(context, SAMPLE);
		DeviceIdentity.forgetToken(context);

		assertFalse(DeviceIdentity.hasToken(context));
		assertNull(DeviceIdentity.token(context));
	}

	@Test
	public void theInstallIdIsStable() {
		assertEquals(DeviceIdentity.installId(context), DeviceIdentity.installId(context));
	}

	private SharedPreferences prefs() {
		return context.createDeviceProtectedStorageContext()
				.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
	}
}
