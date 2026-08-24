package io.github.albertus82.archscan;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.github.junrar.RarFormat;

class ArchiveScannerTest {

	private static final String JDBC_URL = "jdbc:h2:mem:archives;DB_CLOSE_DELAY=-1";

	private static final String EXPECTED_ENTRY_NAME = "lorem.txt";
	private static final long EXPECTED_ENTRY_SIZE = 526L;
	private static final String EXPECTED_CRC = "15C2598D";

	@BeforeAll
	static void beforeAll() throws SQLException, IOException {
		try (final var is = ArchiveScannerTest.class.getResourceAsStream("/ddl-h2.sql"); final var connection = DriverManager.getConnection(JDBC_URL); final var statement = connection.createStatement()) {
			statement.execute(new String(is.readAllBytes(), StandardCharsets.UTF_8));
		}
	}

	@AfterAll
	static void afterAll() throws SQLException {
		/*
		 * Explicitly shut down the in-memory H2 database after the test class has
		 * finished.
		 */
		try (final var connection = DriverManager.getConnection(JDBC_URL); final var statement = connection.createStatement()) {
			statement.execute("SHUTDOWN");
		}
	}

	@Test
	void testAllArchives() throws Exception {
		final Path tempDir = Files.createTempDirectory("archive-scanner-test-");

		try {
			extractTestArchives(tempDir);

			final List<Path> archives;
			try (final var stream = Files.walk(tempDir, 2)) {
				archives = stream.filter(Files::isRegularFile).sorted().toList();
			}

			//assertEquals(20, archives.size(), "archives.zip should contain exactly 20 archive files");

			for (final Path archive : archives) {
				testArchive(archive);
			}
		}
		finally {
			FileUtils.deleteDirectory(tempDir.toFile());
		}
	}

	private static void testArchive(final Path archive) throws Exception {
		final var archiveName = archive.getFileName().toString();

		final var lowerName = archiveName.toLowerCase(Locale.ROOT);

		final var rar = lowerName.endsWith(".rar");
		final var sevenZip = lowerName.endsWith(".7z");
		final var zip = lowerName.endsWith(".zip");

		assertTrue(zip || rar || sevenZip, () -> "Unexpected archive type: " + archiveName);

		final var password = lowerName.contains("password1");
		final var passwordProtected = lowerName.contains("password2");
		final var solid = lowerName.contains("solid");
		final var recovery = lowerName.contains("recovery");

		clearDatabase();

		final int exitCode = ArchiveScanner.call("-R", archive.getParent().toString(), "-J", JDBC_URL, "-S", "MYSCHEMA");

		assertEquals(0, exitCode, () -> "ArchiveScanner returned a non-zero exit code for " + archiveName);

		try (final var connection = DriverManager.getConnection(JDBC_URL)) {
			connection.setSchema("MYSCHEMA");
			/*
			 * Password-protected RAR and 7z archives are deliberately skipped by the
			 * application.
			 */
			final boolean expectedToBeSkipped = passwordProtected;

			if (expectedToBeSkipped) {
				assertSkippedArchive(connection, archiveName);
			}
			else {
				assertProcessedArchive(connection, archiveName, zip, rar, sevenZip, password, solid, recovery, passwordProtected);
			}
		}
	}

	private static void clearDatabase() throws SQLException {
		try (final var connection = DriverManager.getConnection(JDBC_URL); var statement = connection.createStatement()) {
			connection.setSchema("MYSCHEMA");
			statement.executeUpdate("DELETE FROM ZIP_ARCHIVE_ENTRIES");
			statement.executeUpdate("DELETE FROM RAR_ARCHIVE_ENTRIES");
			statement.executeUpdate("DELETE FROM SEVENZIP_ARCHIVE_ENTRIES");
			statement.executeUpdate("DELETE FROM ARCHIVE_ENTRIES");
			statement.executeUpdate("DELETE FROM ZIP_ARCHIVES");
			statement.executeUpdate("DELETE FROM RAR_ARCHIVES");
			statement.executeUpdate("DELETE FROM SEVENZIP_ARCHIVES");
			statement.executeUpdate("DELETE FROM ARCHIVES");
		}
	}

	private static void assertProcessedArchive(final Connection connection, final String archiveName, final boolean zip, final boolean rar, final boolean sevenZip, final boolean password, final boolean solid, final boolean recovery, final boolean passwordProtected) throws SQLException {

		final ArchiveRecord archiveRecord = findArchive(connection, archiveName);

		assertNotNull(archiveRecord, () -> "No ARCHIVES row for " + archiveName);

		assertAll(() -> assertEquals(1, count(connection, "ARCHIVES", "ARCHIVE_NAME = ?", archiveName)),

				() -> assertEquals(1, count(connection, "ARCHIVE_ENTRIES", "ARCHIVE_ID = ?", archiveRecord.archiveId)),

				() -> assertEquals(1, count(connection, typeArchiveTable(zip, rar, sevenZip), "ARCHIVE_ID = ?", archiveRecord.archiveId)),

				() -> assertEquals(1, count(connection, typeEntryTable(zip, rar, sevenZip), "ENTRY_ID IN " + "(SELECT ENTRY_ID " + "FROM ARCHIVE_ENTRIES " + "WHERE ARCHIVE_ID = ?)", archiveRecord.archiveId)));

		final EntryRecord entry = findEntry(connection, archiveRecord.archiveId);

		assertNotNull(entry, () -> "No ARCHIVE_ENTRIES row for " + archiveName);

		assertAll(() -> assertEquals(EXPECTED_ENTRY_NAME, entry.name),

				() -> assertEquals(EXPECTED_ENTRY_SIZE, entry.uncompressedSize));

		/*
		 * The application deliberately stores NULL for an encrypted ZIP entry's CRC.
		 */
		if (!password) {
			assertEquals(EXPECTED_CRC, entry.crc, "CRC for " + archiveName);
		}

		if (password && !sevenZip) {
			assertTrue(entry.encrypted, () -> "Archive should be detected as encrypted: " + archiveName);
		}

		if (rar) {
			assertRarMetadata(connection, archiveName, archiveRecord.archiveId, solid, recovery);
		}
	}

	private static void assertSkippedArchive(final Connection connection, final String archiveName) throws SQLException {

		assertEquals(0, count(connection, "ARCHIVES", "ARCHIVE_NAME = ?", archiveName), () -> "Password-protected archive should be skipped: " + archiveName);
	}

	private static void assertRarMetadata(final Connection connection, final String archiveName, final String archiveId, final boolean expectedSolid, final boolean expectedRecovery) throws SQLException {

		final RarRecord rarRecord = findRarArchive(connection, archiveId);

		assertNotNull(rarRecord, () -> "No RAR_ARCHIVES row for " + archiveName);

		final String expectedVersion = archiveName.toLowerCase().startsWith("r5") ? RarFormat.RAR50.toString() : RarFormat.RAR15.toString();

		assertAll(() -> assertEquals(expectedVersion, rarRecord.formatVersion, "RAR version for " + archiveName));

		if (!archiveName.toLowerCase().startsWith("r5")) {
			assertAll(() -> assertEquals(expectedVersion, rarRecord.formatVersion, "RAR version for " + archiveName),

					() -> assertEquals(expectedSolid, rarRecord.solid, "RAR solid flag for " + archiveName),

					() -> assertEquals(expectedRecovery, rarRecord.isProtected, "RAR recovery information for " + archiveName));
		}
	}

	private static void extractTestArchives(final Path targetDirectory) throws IOException {

		final InputStream resource = Objects.requireNonNull(ArchiveScannerTest.class.getResourceAsStream("/archives.zip"), "Missing /archives.zip");

		int i = 1;
		try (var is = resource; var zis = new ZipInputStream(is)) {
			ZipEntry entry;
			while ((entry = zis.getNextEntry()) != null) {
				if (entry.isDirectory()) {
					continue;
				}

				final Path target = Path.of(targetDirectory.toString(), Integer.toString(i++)).resolve(entry.getName());
				Files.createDirectories(target.getParent());

				/*
				 * We only extract the archive fixture itself. lorem.txt is never opened or read
				 * by this test.
				 */
				Files.copy(zis, target);
			}
		}
	}

	private static int count(final Connection connection, final String table, final String predicate, final Object... parameters) throws SQLException {

		final String sql = "SELECT COUNT(*) FROM " + table + " WHERE " + predicate;

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			setParameters(statement, parameters);

			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static ArchiveRecord findArchive(final Connection connection, final String archiveName) throws SQLException {

		final String sql = """
				SELECT ARCHIVE_ID, ARCHIVE_FORMAT
				FROM ARCHIVES
				WHERE ARCHIVE_NAME = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, archiveName);

			try (ResultSet rs = statement.executeQuery()) {

				if (!rs.next()) {
					return null;
				}

				return new ArchiveRecord(rs.getString("ARCHIVE_ID"), rs.getString("ARCHIVE_FORMAT"));
			}
		}
	}

	private static EntryRecord findEntry(final Connection connection, final String archiveId) throws SQLException {

		final String sql = """
				SELECT ENTRY_NAME,
				       UNCOMPRESSED_SIZE,
				       CRC,
				       IS_ENCRYPTED
				FROM ARCHIVE_ENTRIES
				WHERE ARCHIVE_ID = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, archiveId);

			try (ResultSet rs = statement.executeQuery()) {

				if (!rs.next()) {
					return null;
				}

				return new EntryRecord(rs.getString("ENTRY_NAME"), rs.getLong("UNCOMPRESSED_SIZE"), rs.getString("CRC"), rs.getBoolean("IS_ENCRYPTED"));
			}
		}
	}

	private static RarRecord findRarArchive(final Connection connection, final String archiveId) throws SQLException {

		final String sql = """
				SELECT FORMAT_VERSION,
				       IS_SOLID,
				       IS_PROTECTED
				FROM RAR_ARCHIVES
				WHERE ARCHIVE_ID = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, archiveId);

			try (ResultSet rs = statement.executeQuery()) {

				if (!rs.next()) {
					return null;
				}

				final int recoverySectors = findRecoverySectors(connection, archiveId);

				return new RarRecord(rs.getString("FORMAT_VERSION"), rs.getBoolean("IS_SOLID"), rs.getBoolean("IS_PROTECTED") || recoverySectors > 0);
			}
		}
	}

	private static int findRecoverySectors(final Connection connection, final String archiveId) throws SQLException {

		final String sql = """
				SELECT RE.RECOVERY_SECTORS
				FROM RAR_ARCHIVE_ENTRIES RE
				JOIN ARCHIVE_ENTRIES AE
				  ON AE.ENTRY_ID = RE.ENTRY_ID
				WHERE AE.ARCHIVE_ID = ?
				""";

		try (PreparedStatement statement = connection.prepareStatement(sql)) {

			statement.setString(1, archiveId);

			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
	}

	private static String typeArchiveTable(final boolean zip, final boolean rar, final boolean sevenZip) {

		if (zip) {
			return "ZIP_ARCHIVES";
		}

		if (rar) {
			return "RAR_ARCHIVES";
		}

		if (sevenZip) {
			return "SEVENZIP_ARCHIVES";
		}

		throw new IllegalArgumentException("Unknown archive type");
	}

	private static String typeEntryTable(final boolean zip, final boolean rar, final boolean sevenZip) {

		if (zip) {
			return "ZIP_ARCHIVE_ENTRIES";
		}

		if (rar) {
			return "RAR_ARCHIVE_ENTRIES";
		}

		if (sevenZip) {
			return "SEVENZIP_ARCHIVE_ENTRIES";
		}

		throw new IllegalArgumentException("Unknown archive type");
	}

	private static void setParameters(final PreparedStatement statement, final Object... parameters) throws SQLException {

		for (int i = 0; i < parameters.length; i++) {
			statement.setObject(i + 1, parameters[i]);
		}
	}

	private record ArchiveRecord(String archiveId, String archiveFormat) {}

	private record EntryRecord(String name, long uncompressedSize, String crc, boolean encrypted) {}

	private record RarRecord(String formatVersion, boolean solid, boolean isProtected) {}
}
