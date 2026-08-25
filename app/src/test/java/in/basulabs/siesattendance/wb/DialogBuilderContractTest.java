package in.basulabs.siesattendance.wb;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * A source-level guard against one specific framework trap.
 * <p>
 * AppCompat's {@code AlertController.setupContent} only swaps the dialog's scroll view for
 * its item list in the branch where no message has been set: if {@code mMessage != null} it
 * sets the text and returns, and the {@code ListView} is never attached. A builder given both
 * a message and items therefore renders the message and silently drops every option, leaving
 * whatever buttons were set as the only way out.
 * <p>
 * It compiles, it runs, and it produces a dialog that looks deliberate — which is why it went
 * unnoticed on the lecture editor, where it made the stranded-marks choice unreachable. The
 * compiler cannot see it and lint has no check for it, so it is pinned here.
 */
public class DialogBuilderContractTest {

	private static final Path SOURCE_ROOT =
			Paths.get("src/main/java/in/basulabs/siesattendance");

	/** One builder chain: the text from {@code new …DialogBuilder} to its terminating call. */
	private record Chain(Path file, int line, String text) {
	}

	/**
	 * Every dialog builder chain in the app.
	 * <p>
	 * A chain is taken to run from {@code new …AlertDialogBuilder(} up to the {@code .show()}
	 * or {@code .create()} that ends it, which is how all of them are written here. A chain
	 * with neither is not a dialog being displayed and is skipped.
	 */
	private static List<Chain> dialogChains() throws IOException {

		List<Chain> chains = new ArrayList<>();

		try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {

				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

				for (int i = 0; i < lines.size(); i++) {
					if (!lines.get(i).contains("AlertDialogBuilder(")
							&& !lines.get(i).contains("AlertDialog.Builder(")) {
						continue;
					}

					StringBuilder chain = new StringBuilder();

					for (int j = i; j < lines.size() && j < i + 40; j++) {
						chain.append(lines.get(j)).append('\n');
						String trimmed = lines.get(j).trim();
						if (trimmed.startsWith(".show()") || trimmed.startsWith(".create()")
								|| trimmed.endsWith(".show();") || trimmed.endsWith(".create();")) {
							chains.add(new Chain(file, i + 1, chain.toString()));
							break;
						}
					}
				}
			}
		}
		return chains;
	}

	@Test
	public void theScanFindsTheDialogsItIsSupposedToBeChecking() {
		// A guard that silently matches nothing is worse than no guard, so confirm the scan
		// still sees the app's dialogs before trusting what it says about them.
		List<Chain> chains;
		try {
			chains = dialogChains();
		} catch (IOException e) {
			throw new AssertionError("could not read the sources: " + e.getMessage(), e);
		}

		assertTrue("found only " + chains.size() + " dialog builders; the scan has probably "
				+ "stopped matching and is no longer checking anything", chains.size() >= 15);
	}

	@Test
	public void noDialogSetsBothAMessageAndAListOfItems() throws IOException {

		List<String> offenders = new ArrayList<>();

		for (Chain chain : dialogChains()) {
			if (chain.text().contains(".setMessage(") && chain.text().contains(".setItems(")) {
				offenders.add(chain.file().getFileName() + ":" + chain.line());
			}
		}

		assertTrue("These dialogs set both a message and items, so AppCompat will render the "
						+ "message and drop every option, leaving the user nothing to choose "
						+ "from. Use buttons for the choices, or move the message into the "
						+ "title: " + offenders,
				offenders.isEmpty());
	}
}
