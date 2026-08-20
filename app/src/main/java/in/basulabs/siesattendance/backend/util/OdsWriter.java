package in.basulabs.siesattendance.backend.util;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an OpenDocument spreadsheet (.ods).
 * <p>
 * Hand-rolled rather than pulled from a library. The Java spreadsheet libraries that handle ODS
 * are desktop-oriented and drag in AWT, which does not exist on Android; an ODS file is a zip
 * of a few XML parts, and writing the parts directly costs less than making one of those
 * libraries behave.
 * <p>
 * Only what the attendance sheet needs is implemented: several sheets, string and number cells,
 * and named cell styles for shading. There are no formulas or merged cells. It opens in
 * LibreOffice, Excel, Google Sheets and Collabora.
 */
public final class OdsWriter {

	private static final String MIMETYPE =
			"application/vnd.oasis.opendocument.spreadsheet";

	/** A single cell. Numbers carry a real numeric value so the file can be sorted and summed. */
	public static class Cell {

		@NonNull
		final String text;

		@Nullable
		final Double number;

		@Nullable
		final String styleName;

		private Cell(@NonNull String text, @Nullable Double number, @Nullable String style) {
			this.text = text;
			this.number = number;
			this.styleName = style;
		}

		@NonNull
		public static Cell text(@Nullable String value, @Nullable String style) {
			return new Cell(value == null ? "" : value, null, style);
		}

		@NonNull
		public static Cell number(double value, @Nullable String style) {
			// Whole numbers are shown without a trailing .0; the stored value stays numeric
			// either way, so a reader can still total the column.
			String shown = value == Math.rint(value)
					? String.valueOf((long) value)
					: String.valueOf(value);
			return new Cell(shown, value, style);
		}

		@NonNull
		public static Cell empty() {
			return new Cell("", null, null);
		}
	}

	/** One sheet: a name and its rows. */
	private static class Sheet {

		@NonNull
		final String name;

		final List<List<Cell>> rows = new ArrayList<>();

		Sheet(@NonNull String name) {
			this.name = name;
		}
	}

	private final List<Sheet> sheets = new ArrayList<>();

	/** Style name to its cell properties XML, in declaration order. */
	private final Map<String, String> styles = new LinkedHashMap<>();

	public OdsWriter(@NonNull String firstSheetName) {
		startSheet(firstSheetName);
	}

	/**
	 * Begins a new sheet; subsequent rows go to it.
	 * <p>
	 * Sheet names are sanitised because a reader will reject the file outright for characters
	 * that are illegal in a table name, and a group name comes from the user.
	 */
	public void startSheet(@NonNull String name) {
		sheets.add(new Sheet(sanitiseSheetName(name)));
	}

	/**
	 * Strips the characters ODF forbids in a table name and trims to the 31-character limit
	 * that spreadsheet readers enforce in practice.
	 */
	@NonNull
	private String sanitiseSheetName(@NonNull String raw) {

		String cleaned = raw.replaceAll("[\\\\/:*?\\[\\]']", " ").trim();

		if (cleaned.isEmpty()) {
			cleaned = "Sheet " + (sheets.size() + 1);
		}

		if (cleaned.length() > 31) {
			cleaned = cleaned.substring(0, 31).trim();
		}

		// Two sheets of the same name make an unopenable file, so later duplicates are
		// numbered rather than left to collide.
		String candidate = cleaned;
		int suffix = 2;

		while (nameTaken(candidate)) {
			String tail = " (" + suffix++ + ")";
			candidate = cleaned.length() + tail.length() > 31
					? cleaned.substring(0, 31 - tail.length()) + tail
					: cleaned + tail;
		}
		return candidate;
	}

	private boolean nameTaken(@NonNull String name) {
		for (Sheet sheet : sheets) {
			if (sheet.name.equals(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Declares a named cell style.
	 *
	 * @param background hex colour such as {@code #FFC7CE}, or null to leave it unshaded
	 * @param textColour hex colour for the text, or null for the default
	 */
	public void defineStyle(@NonNull String name, @Nullable String background,
	                        @Nullable String textColour, boolean bold) {

		StringBuilder xml = new StringBuilder();
		xml.append("<style:style style:name=\"").append(name)
				.append("\" style:family=\"table-cell\">");

		if (background != null) {
			xml.append("<style:table-cell-properties fo:background-color=\"")
					.append(background).append("\"/>");
		}

		if (textColour != null || bold) {
			xml.append("<style:text-properties");
			if (textColour != null) {
				xml.append(" fo:color=\"").append(textColour).append("\"");
			}
			if (bold) {
				xml.append(" fo:font-weight=\"bold\"");
			}
			xml.append("/>");
		}

		xml.append("</style:style>");
		styles.put(name, xml.toString());
	}

	public void addRow(@NonNull List<Cell> cells) {
		sheets.get(sheets.size() - 1).rows.add(cells);
	}

	public void addBlankRow() {
		sheets.get(sheets.size() - 1).rows.add(new ArrayList<>());
	}

	/** Writes the whole document. The stream is not closed. */
	public void writeTo(@NonNull OutputStream out) throws IOException {

		ZipOutputStream zip = new ZipOutputStream(out);

		// The mimetype part has to come first and be stored uncompressed, so that a reader can
		// identify the file from its first bytes without inflating anything. This is the one
		// rule of the ODF package that a plain zip writer will not do on its own.
		byte[] mime = MIMETYPE.getBytes(StandardCharsets.US_ASCII);
		ZipEntry mimeEntry = new ZipEntry("mimetype");
		mimeEntry.setMethod(ZipEntry.STORED);
		mimeEntry.setSize(mime.length);
		mimeEntry.setCompressedSize(mime.length);
		CRC32 crc = new CRC32();
		crc.update(mime);
		mimeEntry.setCrc(crc.getValue());
		zip.putNextEntry(mimeEntry);
		zip.write(mime);
		zip.closeEntry();

		zip.setMethod(ZipOutputStream.DEFLATED);

		put(zip, "META-INF/manifest.xml", manifest());
		put(zip, "styles.xml", stylesPart());
		put(zip, "content.xml", content());

		zip.finish();
		zip.flush();
	}

	private void put(@NonNull ZipOutputStream zip, @NonNull String name, @NonNull String body)
			throws IOException {
		zip.putNextEntry(new ZipEntry(name));
		zip.write(body.getBytes(StandardCharsets.UTF_8));
		zip.closeEntry();
	}

	@NonNull
	private String manifest() {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<manifest:manifest "
				+ "xmlns:manifest=\"urn:oasis:names:tc:opendocument:xmlns:manifest:1.0\" "
				+ "manifest:version=\"1.2\">"
				+ "<manifest:file-entry manifest:full-path=\"/\" "
				+ "manifest:media-type=\"" + MIMETYPE + "\"/>"
				+ "<manifest:file-entry manifest:full-path=\"content.xml\" "
				+ "manifest:media-type=\"text/xml\"/>"
				+ "<manifest:file-entry manifest:full-path=\"styles.xml\" "
				+ "manifest:media-type=\"text/xml\"/>"
				+ "</manifest:manifest>";
	}

	@NonNull
	private String stylesPart() {
		return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
				+ "<office:document-styles "
				+ "xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
				+ "xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" "
				+ "xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:"
				+ "xsl-fo-compatible:1.0\" "
				+ "office:version=\"1.2\"><office:styles/></office:document-styles>";
	}

	@NonNull
	private String content() {

		StringBuilder xml = new StringBuilder(4096);

		xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
				.append("<office:document-content ")
				.append("xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" ")
				.append("xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\" ")
				.append("xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\" ")
				.append("xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\" ")
				.append("xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:")
				.append("xsl-fo-compatible:1.0\" ")
				.append("office:version=\"1.2\">");

		xml.append("<office:automatic-styles>");
		for (String style : styles.values()) {
			xml.append(style);
		}
		xml.append("</office:automatic-styles>");

		xml.append("<office:body><office:spreadsheet>");

		for (Sheet sheet : sheets) {

			xml.append("<table:table table:name=\"").append(escape(sheet.name)).append("\">");

			int widest = 1;
			for (List<Cell> row : sheet.rows) {
				widest = Math.max(widest, row.size());
			}
			xml.append("<table:table-column table:number-columns-repeated=\"")
					.append(widest).append("\"/>");

			for (List<Cell> row : sheet.rows) {
				xml.append("<table:table-row>");
				for (Cell cell : row) {
					appendCell(xml, cell);
				}
				xml.append("</table:table-row>");
			}
			xml.append("</table:table>");
		}

		xml.append("</office:spreadsheet></office:body>")
				.append("</office:document-content>");

		return xml.toString();
	}

	private void appendCell(@NonNull StringBuilder xml, @NonNull Cell cell) {

		xml.append("<table:table-cell");

		if (cell.styleName != null) {
			xml.append(" table:style-name=\"").append(cell.styleName).append("\"");
		}

		if (cell.number != null) {
			xml.append(" office:value-type=\"float\" office:value=\"")
					.append(cell.number).append("\"");
		} else if (!cell.text.isEmpty()) {
			xml.append(" office:value-type=\"string\"");
		}

		if (cell.text.isEmpty() && cell.number == null) {
			xml.append("/>");
			return;
		}

		xml.append("><text:p>").append(escape(cell.text)).append("</text:p>")
				.append("</table:table-cell>");
	}

	/**
	 * A student's name is arbitrary text and will eventually contain an ampersand or an angle
	 * bracket, which would otherwise produce a file no reader can open.
	 */
	@NonNull
	private static String escape(@NonNull String raw) {
		return raw.replace("&", "&amp;")
				.replace("<", "&lt;")
				.replace(">", "&gt;")
				.replace("\"", "&quot;")
				.replace("'", "&apos;");
	}
}
