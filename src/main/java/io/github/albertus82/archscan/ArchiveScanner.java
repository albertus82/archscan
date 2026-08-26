package io.github.albertus82.archscan;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import org.apache.commons.compress.PasswordRequiredException;
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.lang3.tuple.Triple;

import com.github.junrar.Archive;
import com.github.junrar.exception.RarException;
import com.github.junrar.exception.WrongPasswordException;
import com.github.junrar.rarfile.FileHeader;
import com.github.junrar.rarfile.rar5.Rar5HashType;

import net.lingala.zip4j.model.enums.EncryptionMethod;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.ExitCode;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@SuppressWarnings("java:S106")
@Command(name = "archscan", sortSynopsis = false, sortOptions = false, mixinStandardHelpOptions = true, versionProvider = VersionProvider.class, description = "Scans ZIP, RAR and 7-Zip archives and stores their metadata into the configured database.")
final class ArchiveScanner implements Callable<Integer> {

	private static final String TAB_ARCHIVES = "ARCHIVES";
	private static final String TAB_ZIP_ARCHIVES = "ZIP_ARCHIVES";
	private static final String TAB_RAR_ARCHIVES = "RAR_ARCHIVES";
	private static final String TAB_SEVENZIP_ARCHIVES = "SEVENZIP_ARCHIVES";

	private static final String TAB_ARCHIVE_ENTRIES = "ARCHIVE_ENTRIES";
	private static final String TAB_ZIP_ARCHIVE_ENTRIES = "ZIP_ARCHIVE_ENTRIES";
	private static final String TAB_RAR_ARCHIVE_ENTRIES = "RAR_ARCHIVE_ENTRIES";
	private static final String TAB_SEVENZIP_ARCHIVE_ENTRIES = "SEVENZIP_ARCHIVE_ENTRIES";

	private static final String COL_ARCHIVE_ID = "ARCHIVE_ID";
	private static final String COL_ARCHIVE_PATH = "ARCHIVE_PATH";
	private static final String COL_ARCHIVE_NAME = "ARCHIVE_NAME";
	private static final String COL_ARCHIVE_SIZE = "ARCHIVE_SIZE";
	private static final String COL_LAST_MODIFIED = "LAST_MODIFIED";

	private static final String COL_ENTRY_ID = "ENTRY_ID";

	@Parameters(paramLabel = "<path>", description = "Base directory to scan.")
	private Path basePath;

	@Option(names = { "-R", "--recursive" }, description = "Scan subdirectories recursively.")
	private boolean recursive;

	@Option(names = { "-L", "--follow-links" }, description = "Follow symbolic links while scanning.")
	private boolean followLinks;

	@Option(names = { "-T", "--ignore-date" }, description = "Ignore the last modified date when matching archives against database records; match by path and size only.")
	private boolean ignoreDate;

	@Option(names = { "-M", "--path-mode" }, converter = PathModeConverter.class, defaultValue = "relative", paramLabel = "<mode>", description = { "How archive paths are stored.", "Valid values: relative (R), parent (P), absolute (A).", "Default: ${DEFAULT-VALUE}." })
	private PathMode pathMode;

	@Option(names = { "-K", "--key-type" }, defaultValue = "uuid", paramLabel = "<type>", description = { "Type of keys generated for database rows.", "Valid values: sequence, uuid.", "Default: ${DEFAULT-VALUE}." })
	private KeyType keyType;

	@Option(names = { "-J", "--db-url" }, required = true, paramLabel = "<jdbc-url>", description = "Database JDBC connection URL.")
	private String jdbcUrl;

	@Option(names = { "-S", "--db-schema" }, paramLabel = "<schema>", description = "Database schema.")
	private String dbSchema;

	@Option(names = { "-U", "--db-user" }, paramLabel = "<username>", description = "Database username.")
	private String dbUser;

	@Option(names = { "-P", "--db-password" }, paramLabel = "<password>", interactive = true, arity = "0..1", prompt = "Enter database password: ", description = { "Database password.", "If omitted, you will be prompted securely. Avoid specifying the password on the command line because it may be visible in process listings, shell history, or logs." })
	private char[] dbPassword;

	public static void main(final String... args) {
		final var exitCode = call(args);
		System.exit(exitCode);
	}

	static int call(final String... args) {
		return new CommandLine(new ArchiveScanner()).setUsageHelpAutoWidth(true).setOptionsCaseInsensitive(true).setCaseInsensitiveEnumValuesAllowed(true).setSubcommandsCaseInsensitive(true).execute(args);
	}

	@Override
	public Integer call() throws SQLException, IOException, RarException {
		final var t0 = System.nanoTime();
		System.out.println("Archive Metadata Scanner");
		System.out.println("========================");
		System.out.println();
		System.out.flush();

		try {
			this.basePath = this.basePath.toRealPath(followLinks ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS });
		}
		catch (final IOException e) {
			System.err.print("Unable to resolve base path: ");
			System.err.flush();
			e.printStackTrace(System.err);
			return ExitCode.SOFTWARE;
		}

		System.out.println("Configuration:");
		System.out.println(" - Base path: " + basePath);
		System.out.println(" - Recursive: " + (recursive ? "yes" : "no"));
		System.out.println(" - Follow links: " + (followLinks ? "yes" : "no"));
		System.out.println(" - Ignore date: " + (ignoreDate ? "yes" : "no"));
		System.out.println(" - Path mode: " + String.valueOf(pathMode).toLowerCase(Locale.ROOT));
		System.out.println(" - Key type: " + String.valueOf(keyType).toLowerCase(Locale.ROOT));
		System.out.println(" - Database URL: " + jdbcUrl);
		if (dbSchema != null) {
			System.out.println(" - Database schema: " + dbSchema);
		}
		if (dbUser != null) {
			System.out.println(" - Database user: " + dbUser);
		}
		System.out.println();
		System.out.flush();

		long deleteCount = 0;
		long insertCount = 0;
		try (final var connection = dbUser == null ? DriverManager.getConnection(jdbcUrl) : DriverManager.getConnection(jdbcUrl, dbUser, dbPassword == null ? null : String.valueOf(dbPassword))) {
			if (dbPassword != null) {
				Arrays.fill(dbPassword, '\0');
			}
			connection.setAutoCommit(false);
			if (dbSchema != null) {
				connection.setSchema(dbSchema);
			}

			System.out.println("Checking for modified or deleted archives...");
			final var archivePaths = scan();

			deleteCount = purgeMissingArchives(archivePaths, connection);
			System.out.println();

			System.out.println("Processing archives...");
			insertCount = processArchives(archivePaths, connection);
			System.out.println();
		}
		finally {
			System.out.println(deleteCount + " archive(s) removed from database.");
			System.out.println(insertCount + " archive(s) added to database.");
			System.out.println("Elapsed time: " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) / 1000f + " seconds.");
		}
		return ExitCode.OK;
	}

	private long processArchives(final Iterable<Path> archivePaths, final Connection connection) throws SQLException, IOException, RarException {
		long count = 0;
		for (final var archivePath : archivePaths) {
			if (processArchive(archivePath, connection)) {
				count++;
			}
		}
		return count;
	}

	private boolean processArchive(final Path archivePath, final Connection connection) throws SQLException, IOException, RarException {
		if (archiveAlreadyProcessed(connection, archivePath)) {
			System.out.println(" - Skipping already processed archive: " + archivePath);
			return false;
		}

		System.out.println(" - Processing archive: " + archivePath);
		final var archiveType = ArchiveType.fromPath(archivePath);
		final var t0 = System.nanoTime();
		final var processed = switch (archiveType) {
		case ZIP -> processZipArchive(connection, archivePath);
		case RAR -> processRarArchive(connection, archivePath);
		case SEVENZIP -> processSevenZipArchive(connection, archivePath);
		default -> throw new UnsupportedOperationException(String.valueOf(archivePath));
		};
		if (processed) {
			System.out.println("    * " + archiveType.getDescription() + " archive processed successfully in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) + " ms: " + archivePath);
		}
		return processed;
	}

	private boolean processZipArchive(final Connection connection, final Path archivePath) throws SQLException, IOException {
		final var archiveRow = new ZipArchiveRow();
		archiveRow.archivePath = toStoredPath(archivePath, true);
		archiveRow.archiveName = archivePath.getFileName().toString();
		archiveRow.archiveSize = Files.size(archivePath);
		archiveRow.lastModified = dateOf(Files.getLastModifiedTime(archivePath));
		archiveRow.archiveFormat = ArchiveType.ZIP;

		try (final var ccZipFile = org.apache.commons.compress.archivers.zip.ZipFile.builder().setPath(archivePath).get(); final var z4jZipFile = new net.lingala.zip4j.ZipFile(archivePath.toFile())) {
			final var ccEntries = new ArrayList<ZipArchiveEntry>();
			final var enumeration = ccZipFile.getEntries();
			while (enumeration.hasMoreElements()) {
				ccEntries.add(enumeration.nextElement());
			}
			final var z4jEntries = z4jZipFile.getFileHeaders();

			archiveRow.archiveComment = z4jZipFile.getComment();
			archiveRow.entryCount = ccEntries.size();
			archiveRow.totalPackedSize = sumZipPackedSize(ccEntries);
			archiveRow.totalUnpackedSize = sumZipUnpackedSize(ccEntries);
			archiveRow.zipEncoding = ccZipFile.getEncoding();
			archiveRow.zipFirstLocalFileHeaderOffset = ccZipFile.getFirstLocalFileHeaderOffset();

			final var archiveId = insertZipArchive(connection, archiveRow);

			int index = 0;
			for (final var ccEntry : ccEntries) {
				final var match = z4jEntries.stream().filter(header -> header.getFileName().equals(ccEntry.getName())).toList();
				if (match.size() != 1) {
					throw new IllegalStateException();
				}
				final var row = toZipEntryRow(archiveId, index++, ccEntry, match.getFirst());
				insertZipEntry(connection, row);
			}

			connection.commit();
			return true;
		}
		catch (final SQLException e) {
			rollbackQuietly(connection);
			throw e;
		}
	}

	private boolean processRarArchive(final Connection connection, final Path archivePath) throws SQLException, IOException, RarException {
		final var archiveRow = new RarArchiveRow();
		archiveRow.archivePath = toStoredPath(archivePath, true);
		archiveRow.archiveName = archivePath.getFileName().toString();
		archiveRow.archiveSize = Files.size(archivePath);
		archiveRow.lastModified = dateOf(Files.getLastModifiedTime(archivePath));
		archiveRow.archiveFormat = ArchiveType.RAR;

		try (final var rarArchive = new Archive(archivePath.toFile())) {
			final var fileHeaders = rarArchive.getFileHeaders();
			final var mainHeader = rarArchive.getMainHeader();

			archiveRow.formatVersion = String.valueOf(rarArchive.getFormat());
			archiveRow.archiveComment = null;
			archiveRow.entryCount = fileHeaders.size();
			archiveRow.totalPackedSize = sumRarPackedSize(fileHeaders);
			archiveRow.totalUnpackedSize = sumRarUnpackedSize(fileHeaders);
			archiveRow.isSolid = mainHeader != null && mainHeader.isSolid();
			archiveRow.isLocked = mainHeader != null && mainHeader.isLocked();
			archiveRow.isProtected = mainHeader != null && mainHeader.isProtected();
			archiveRow.isAv = mainHeader != null && mainHeader.isAV();
			archiveRow.isNewNumbering = mainHeader != null && mainHeader.isNewNumbering();
			archiveRow.isMultiVolume = mainHeader != null && mainHeader.isMultiVolume();
			archiveRow.isFirstVolume = mainHeader != null && mainHeader.isFirstVolume();
			archiveRow.isEncrypted = mainHeader != null && rarArchive.isEncrypted();
			archiveRow.isPasswordProtected = mainHeader != null && rarArchive.isPasswordProtected();
			archiveRow.hasArchiveComment = mainHeader != null && mainHeader.hasArchCmt();
			archiveRow.highPosAv = mainHeader == null ? null : Long.valueOf(mainHeader.getHighPosAv());
			archiveRow.posAv = mainHeader == null ? null : Long.valueOf(mainHeader.getPosAv());
			archiveRow.encryptVersion = mainHeader == null ? null : Long.valueOf(mainHeader.getEncryptVersion());
			archiveRow.recoveryDataSize = null;
			archiveRow.flags = mainHeader != null && mainHeader.getFlags() >= 0 ? String.format("%04X", mainHeader.getFlags()) : null;

			final var archiveId = insertRarArchive(connection, archiveRow);

			int index = 0;
			for (final var header : fileHeaders) {
				final var row = toRarEntryRow(archiveId, index++, header, rarArchive);
				insertRarEntry(connection, row);
			}

			connection.commit();
			return true;
		}
		catch (final WrongPasswordException e) {
			System.err.println("    * Skipping password-protected RAR archive: " + archivePath);
			return false;
		}
		catch (final SQLException e) {
			rollbackQuietly(connection);
			throw e;
		}
	}

	private boolean processSevenZipArchive(final Connection connection, final Path archivePath) throws SQLException, IOException {
		final var archiveRow = new SevenZipArchiveRow();
		archiveRow.archivePath = toStoredPath(archivePath, true);
		archiveRow.archiveName = archivePath.getFileName().toString();
		archiveRow.archiveSize = Files.size(archivePath);
		archiveRow.lastModified = dateOf(Files.getLastModifiedTime(archivePath));
		archiveRow.archiveFormat = ArchiveType.SEVENZIP;

		try (final var sevenZFile = SevenZFile.builder().setPath(archivePath).get()) {
			final var entries = new ArrayList<SevenZArchiveEntry>();
			for (final var entry : sevenZFile.getEntries()) {
				entries.add(entry);
			}

			archiveRow.archiveComment = null;
			archiveRow.entryCount = entries.size();
			archiveRow.totalPackedSize = null;
			archiveRow.totalUnpackedSize = sumSevenZipUnpackedSize(entries);

			final var archiveId = insertSevenZipArchive(connection, archiveRow);

			int index = 0;
			for (final var entry : entries) {
				final var row = toSevenZipEntryRow(archiveId, index++, entry);
				insertSevenZipEntry(connection, row);
			}

			connection.commit();
			return true;
		}
		catch (final PasswordRequiredException e) {
			System.err.println("    * Skipping password-protected 7-Zip archive: " + archivePath);
			return false;
		}
		catch (final SQLException e) {
			rollbackQuietly(connection);
			throw e;
		}
	}

	private static ZipEntryRow toZipEntryRow(final Serializable archiveId, final int index, final ZipArchiveEntry ccEntry, final net.lingala.zip4j.model.FileHeader z4jEntry) {
		final var row = new ZipEntryRow();
		final var encrypted = z4jEntry.isEncrypted();

		row.archiveId = archiveId;
		row.entryIndex = index;
		final var rawName = getRawName(ccEntry.getName());
		final var lastSlash = rawName.lastIndexOf('/');
		row.entryPath = lastSlash >= 0 ? rawName.substring(0, lastSlash + 1) : null;
		row.entryName = lastSlash >= 0 ? rawName.substring(lastSlash + 1) : rawName;
		row.isDirectory = ccEntry.isDirectory();
		row.isSymbolicLink = ccEntry.isUnixSymlink();
		row.uncompressedSize = ccEntry.isDirectory() ? null : ccEntry.getSize();
		row.compressedSize = ccEntry.isDirectory() ? null : ccEntry.getCompressedSize();
		row.crc = !encrypted && !ccEntry.isDirectory() && ccEntry.getCrc() > -1 ? String.format("%08X", ccEntry.getCrc()) : null;
		row.method = ccEntry.getMethod() == -1 ? null : ccEntry.getMethod();
		row.lastModified = dateOf(ccEntry.getLastModifiedTime());
		row.lastAccess = dateOf(ccEntry.getLastAccessTime());
		row.creationTime = dateOf(ccEntry.getCreationTime());
		row.comment = ccEntry.getComment();
		row.platform = String.valueOf(ccEntry.getPlatform());
		row.internalAttributes = Integer.toUnsignedLong(ccEntry.getInternalAttributes());
		row.externalAttributes = ccEntry.getExternalAttributes();
		row.unixMode = Integer.toUnsignedLong(ccEntry.getUnixMode());
		row.dataOffset = ccEntry.getLocalHeaderOffset();
		row.isEncrypted = encrypted;
		row.flagDataDescriptor = z4jEntry.isDataDescriptorExists();
		row.encryptionMethod = EncryptionMethod.NONE.equals(z4jEntry.getEncryptionMethod()) ? null : String.valueOf(z4jEntry.getEncryptionMethod());
		row.isUnicode = z4jEntry.isFileNameUTF8Encoded();
		row.extraFieldCount = ccEntry.getExtraFields() == null ? null : (long) ccEntry.getExtraFields().length;
		row.localExtraLength = bytesLength(ccEntry.getLocalFileDataExtra());
		row.centralExtraLength = bytesLength(ccEntry.getCentralDirectoryExtra());
		row.versionMadeBy = ccEntry.getVersionMadeBy();
		row.versionRequired = ccEntry.getVersionRequired();
		row.diskNumberStart = ccEntry.getDiskNumberStart();

		return row;
	}

	private static RarEntryRow toRarEntryRow(final Serializable archiveId, final int index, final FileHeader header, final Archive rarArchive) throws RarException {
		final var row = new RarEntryRow();

		row.archiveId = archiveId;
		row.entryIndex = index;
		final var rawName = getRawName(header.getFileName());
		final var lastSlash = rawName.lastIndexOf('/');
		row.entryPath = lastSlash >= 0 ? rawName.substring(0, lastSlash + 1) : null;
		row.entryName = lastSlash >= 0 ? rawName.substring(lastSlash + 1) : rawName;
		row.isDirectory = header.isDirectory();
		row.isSymbolicLink = null;
		row.uncompressedSize = header.isDirectory() ? null : header.getFullUnpackSize();
		row.compressedSize = header.isDirectory() ? null : header.getFullPackSize();
		final var crc = Integer.toUnsignedLong(header.getFileCRC());
		row.crc = !Rar5HashType.BLAKE2.equals(header.getHashType()) && !header.isEncrypted() && !header.isDirectory() && crc > -1 ? String.format("%08X", crc) : null;
		row.method = Byte.toUnsignedInt(header.getUnpMethod());
		row.lastModified = dateOf(header.getLastModifiedTime());
		row.lastAccess = dateOf(header.getLastAccessTime());
		row.creationTime = dateOf(header.getCreationTime());
		row.comment = null;
		row.platform = String.valueOf(header.getHostOS());
		row.internalAttributes = null;
		row.externalAttributes = null;
		row.unixMode = null;
		row.dataOffset = rarArchive.getMainHeader() == null ? null : header.getDataStartOffset(rarArchive.isEncrypted());
		row.versionRequired = Byte.toUnsignedInt(header.getUnpVersion());
		row.recoverySectors = header.getRecoverySectors();
		row.isSolid = header.isSolid();
		row.isEncrypted = header.isEncrypted();
		row.isUnicode = header.isUnicode();
		row.isSplitBefore = header.isSplitBefore();
		row.isSplitAfter = header.isSplitAfter();
		row.isRar5Container = header.isRar5Container();
		row.isRar5Family = header.isRar5Family();
		row.hashType = header.getHashType() == null ? null : header.getHashType().toString();
		row.hashDigest = header.getHashDigest() != null && header.getHashDigest().length > 0 ? String.format("%x", new BigInteger(header.getHashDigest())) : null;

		return row;
	}

	private static SevenZipEntryRow toSevenZipEntryRow(final Serializable archiveId, final int index, final SevenZArchiveEntry entry) {
		final var row = new SevenZipEntryRow();

		row.archiveId = archiveId;
		row.entryIndex = index;
		final var rawName = getRawName(entry.getName());
		final var lastSlash = rawName.lastIndexOf('/');
		row.entryPath = lastSlash >= 0 ? rawName.substring(0, lastSlash + 1) : null;
		row.entryName = lastSlash >= 0 ? rawName.substring(lastSlash + 1) : rawName;
		row.isDirectory = entry.isDirectory();
		row.isSymbolicLink = null;
		row.uncompressedSize = entry.hasStream() && !entry.isDirectory() ? entry.getSize() : null;
		row.compressedSize = null;
		row.crc = entry.getHasCrc() && entry.hasStream() && !entry.isDirectory() ? String.format("%08X", entry.getCrcValue()) : null;
		row.method = null;
		row.lastModified = entry.getHasLastModifiedDate() ? dateOf(entry.getLastModifiedTime()) : null;
		row.lastAccess = entry.getHasAccessDate() ? dateOf(entry.getAccessTime()) : null;
		row.creationTime = entry.getHasCreationDate() ? dateOf(entry.getCreationTime()) : null;
		row.comment = null;
		row.platform = null;
		row.internalAttributes = null;
		row.externalAttributes = null;
		row.unixMode = null;
		row.dataOffset = null;
		row.versionRequired = null;
		row.isEncrypted = sevenZipIsEncrypted(entry);
		row.isUnicode = null;
		row.methods = sevenZipMethods(entry.getContentMethods());
		row.windowsAttributes = entry.getHasWindowsAttributes() ? String.format("%08X", entry.getWindowsAttributes()) : null;
		row.hasStream = entry.hasStream();
		row.emptyStream = entry.isEmptyStream();
		row.antiItem = entry.isAntiItem();

		return row;
	}

	private static String getRawName(final String entryName) {
		var rawName = normalizeFileSeparators(entryName);
		if (rawName.endsWith("/")) {
			rawName = rawName.substring(0, rawName.length() - 1);
		}
		return rawName;
	}

	private static boolean archiveAlreadyProcessed(final Connection connection, final Path archivePath) throws SQLException, IOException {
		final var sql = String.format("SELECT 1 FROM %s WHERE %s=? AND %s=?", TAB_ARCHIVES, COL_ARCHIVE_NAME, COL_ARCHIVE_SIZE);
		try (final var ps = connection.prepareStatement(sql)) {
			ps.setString(1, archivePath.getFileName().toString());
			ps.setLong(2, Files.size(archivePath));
			try (final var rs = ps.executeQuery()) {
				return rs.next();
			}
		}
	}

	private Serializable generateKey(final Connection connection, final String tableName) throws SQLException {
		switch (keyType) {
		case SEQUENCE:
			final var sql = String.format("SELECT %s_SEQ.NEXTVAL FROM DUAL", tableName);
			try (final var ps = connection.prepareStatement(sql); final var rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		case UUID:
			final var uuid = UUID.randomUUID();
			return Base64.getUrlEncoder().withoutPadding().encodeToString(ByteBuffer.allocate(Long.BYTES * 2).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array());
		default:
			throw new UnsupportedOperationException(String.valueOf(keyType));
		}
	}

	private Serializable insertArchive(final Connection connection, final ArchiveRow row) throws SQLException {
		final var archiveId = generateKey(connection, TAB_ARCHIVES);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    %s,
				    %s,
				    %s,
				    %s,
				    archive_format,
				    archive_comment,
				    entry_count,
				    total_packed_size,
				    total_unpacked_size
				) VALUES (
				    ?,?,?,?,?,?,?,?,?,?
				)""".trim(), TAB_ARCHIVES, COL_ARCHIVE_ID, COL_ARCHIVE_PATH, COL_ARCHIVE_NAME, COL_ARCHIVE_SIZE, COL_LAST_MODIFIED))) {
			int i = 1;

			// ARCHIVE_ID
			if (archiveId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, archiveId.toString());
			}
			setString(ps, i++, row.archivePath); // ARCHIVE_PATH
			setString(ps, i++, row.archiveName); // ARCHIVE_NAME
			setLong(ps, i++, row.archiveSize); // ARCHIVE_SIZE
			setTimestamp(ps, i++, row.lastModified); // LAST_MODIFIED
			setString(ps, i++, row.archiveFormat.getExtension()); // ARCHIVE_FORMAT
			setClob(ps, i++, row.archiveComment); // ARCHIVE_COMMENT
			setInt(ps, i++, row.entryCount); // ENTRY_COUNT
			setLong(ps, i++, row.totalPackedSize); // TOTAL_PACKED_SIZE
			setLong(ps, i++, row.totalUnpackedSize); // TOTAL_UNPACKED_SIZE

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return archiveId;
	}

	private Serializable insertZipArchive(final Connection connection, final ZipArchiveRow row) throws SQLException {
		final var archiveId = insertArchive(connection, row);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    encoding,
				    first_local_file_hdr_offset
				) VALUES (
				    ?,?,?
				)""".trim(), TAB_ZIP_ARCHIVES, COL_ARCHIVE_ID))) {
			int i = 1;

			// ARCHIVE_ID
			if (archiveId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, archiveId.toString());
			}
			setString(ps, i++, row.zipEncoding); // ENCODING
			setLong(ps, i++, row.zipFirstLocalFileHeaderOffset); // 1ST_LOCAL_FILE_HDR_OFFSET

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return archiveId;
	}

	private Serializable insertRarArchive(final Connection connection, final RarArchiveRow row) throws SQLException {
		final var archiveId = insertArchive(connection, row);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    format_version,
				    is_solid,
				    is_locked,
				    is_protected,
				    is_av,
				    is_new_numbering,
				    is_multi_volume,
				    is_first_volume,
				    is_encrypted,
				    is_password_protected,
				    has_archive_comment,
				    high_pos_av,
				    pos_av,
				    encrypt_version,
				    recovery_data_size,
				    flags
				) VALUES (
				    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
				)""".trim(), TAB_RAR_ARCHIVES, COL_ARCHIVE_ID))) {
			int i = 1;

			// ARCHIVE_ID
			if (archiveId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, archiveId.toString());
			}
			setString(ps, i++, row.formatVersion); // ARCHIVE_FORMAT_VERSION
			setBoolean(ps, i++, row.isSolid); // IS_SOLID
			setBoolean(ps, i++, row.isLocked); // IS_LOCKED
			setBoolean(ps, i++, row.isProtected); // IS_PROTECTED
			setBoolean(ps, i++, row.isAv); // IS_AV
			setBoolean(ps, i++, row.isNewNumbering); // IS_NEW_NUMBERING
			setBoolean(ps, i++, row.isMultiVolume); // IS_MULTI_VOLUME
			setBoolean(ps, i++, row.isFirstVolume); // IS_FIRST_VOLUME
			setBoolean(ps, i++, row.isEncrypted); // IS_ENCRYPTED
			setBoolean(ps, i++, row.isPasswordProtected); // IS_PASSWORD_PROTECTED
			setBoolean(ps, i++, row.hasArchiveComment); // HAS_ARCHIVE_COMMENT
			setLong(ps, i++, row.highPosAv); // HIGH_POS_AV
			setLong(ps, i++, row.posAv); // POS_AV
			setLong(ps, i++, row.encryptVersion); // ENCRYPT_VERSION
			setLong(ps, i++, row.recoveryDataSize); // RECOVERY_DATA_SIZE
			setString(ps, i++, row.flags); // FLAGS

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return archiveId;
	}

	private Serializable insertSevenZipArchive(final Connection connection, final SevenZipArchiveRow row) throws SQLException {
		final var archiveId = insertArchive(connection, row);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s
				) VALUES (
				    ?
				)""".trim(), TAB_SEVENZIP_ARCHIVES, COL_ARCHIVE_ID))) {
			int i = 1;

			// ARCHIVE_ID
			if (archiveId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, archiveId.toString());
			}
			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return archiveId;
	}

	private Serializable insertEntry(final Connection connection, final EntryRow row) throws SQLException {
		final var entryId = generateKey(connection, TAB_ARCHIVE_ENTRIES);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    %s,
				    entry_index,
				    entry_path,
				    entry_name,
				    is_directory,
				    is_symbolic_link,
				    uncompressed_size,
				    compressed_size,
				    crc,
				    method,
				    last_modified,
				    last_access,
				    creation_time,
				    entry_comment,
				    platform,
				    internal_attributes,
				    external_attributes,
				    unix_mode,
				    data_offset,
				    version_required,
				    is_encrypted,
				    is_unicode
				) VALUES (
				    ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
				)""".trim(), TAB_ARCHIVE_ENTRIES, COL_ENTRY_ID, COL_ARCHIVE_ID))) {
			int i = 1;

			// ENTRY_ID
			if (entryId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, entryId.toString());
			}
			// ARCHIVE_ID
			if (row.archiveId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, row.archiveId.toString());
			}
			setInt(ps, i++, row.entryIndex); // ENTRY_INDEX
			setString(ps, i++, row.entryPath); // ENTRY_PATH
			setString(ps, i++, row.entryName); // ENTRY_NAME
			setBoolean(ps, i++, row.isDirectory); // IS_DIRECTORY
			setBoolean(ps, i++, row.isSymbolicLink); // IS_SYMBOLIC_LINK
			setLong(ps, i++, row.uncompressedSize); // UNCOMPRESSED_SIZE
			setLong(ps, i++, row.compressedSize); // COMPRESSED_SIZE
			setString(ps, i++, row.crc); // CRC
			setInt(ps, i++, row.method); // METHOD
			setTimestamp(ps, i++, row.lastModified); // LAST_MODIFIED
			setTimestamp(ps, i++, row.lastAccess); // LAST_ACCESS
			setTimestamp(ps, i++, row.creationTime); // CREATION_TIME
			setClob(ps, i++, row.comment); // ENTRY_COMMENT
			setString(ps, i++, row.platform); // PLATFORM
			setLong(ps, i++, row.internalAttributes); // INTERNAL_ATTRIBUTES
			setLong(ps, i++, row.externalAttributes); // EXTERNAL_ATTRIBUTES
			setLong(ps, i++, row.unixMode); // UNIX_MODE
			setLong(ps, i++, row.dataOffset); // DATA_OFFSET
			setInt(ps, i++, row.versionRequired); // VERSION_REQUIRED
			setBoolean(ps, i++, row.isEncrypted); // IS_ENCRYPTED
			setBoolean(ps, i++, row.isUnicode); // IS_UNICODE

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return entryId;
	}

	private Serializable insertZipEntry(final Connection connection, final ZipEntryRow row) throws SQLException {
		final var entryId = insertEntry(connection, row);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    flag_data_descriptor,
				    encryption_method,
				    extra_field_count,
				    local_extra_length,
				    central_extra_length,
				    version_made_by,
				    disk_number_start
				) VALUES (
				    ?,?,?,?,?,?,?,?
				)""".trim(), TAB_ZIP_ARCHIVE_ENTRIES, COL_ENTRY_ID))) {
			int i = 1;

			// ENTRY_ID
			if (entryId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, entryId.toString());
			}
			setBoolean(ps, i++, row.flagDataDescriptor); // FLAG_DATA_DESCRIPTOR
			setString(ps, i++, row.encryptionMethod); // ENCRYPTION_METHOD
			setLong(ps, i++, row.extraFieldCount); // EXTRA_FIELD_COUNT
			setLong(ps, i++, row.localExtraLength); // LOCAL_EXTRA_LENGTH
			setLong(ps, i++, row.centralExtraLength); // CENTRAL_EXTRA_LENGTH
			setInt(ps, i++, row.versionMadeBy); // VERSION_MADE_BY
			setLong(ps, i++, row.diskNumberStart); // DISK_NUMBER_START

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return entryId;
	}

	private Serializable insertRarEntry(final Connection connection, final RarEntryRow row) throws SQLException {
		final var entryId = insertEntry(connection, row);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    recovery_sectors,
				    is_solid,
				    is_split_before,
				    is_split_after,
				    is_rar5_container,
				    is_rar5_family,
				    hash_type,
				    hash_digest
				) VALUES (
				    ?,?,?,?,?,?,?,?,?
				)""".trim(), TAB_RAR_ARCHIVE_ENTRIES, COL_ENTRY_ID))) {
			int i = 1;

			// ENTRY_ID
			if (entryId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, entryId.toString());
			}
			setInt(ps, i++, row.recoverySectors); // RECOVERY_SECTORS
			setBoolean(ps, i++, row.isSolid); // IS_SOLID
			setBoolean(ps, i++, row.isSplitBefore); // IS_SPLIT_BEFORE
			setBoolean(ps, i++, row.isSplitAfter); // IS_SPLIT_AFTER
			setBoolean(ps, i++, row.isRar5Container); // IS_RAR5_CONTAINER
			setBoolean(ps, i++, row.isRar5Family); // IS_RAR5_FAMILY
			setString(ps, i++, row.hashType); // HASH_TYPE
			setString(ps, i++, row.hashDigest); // HASH_DIGEST

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return entryId;
	}

	private Serializable insertSevenZipEntry(final Connection connection, final SevenZipEntryRow row) throws SQLException {
		final var entryId = insertEntry(connection, row);

		try (final var ps = connection.prepareStatement(String.format("""
				INSERT INTO %s (
				    %s,
				    methods,
				    windows_attributes,
				    has_crc,
				    has_stream,
				    empty_stream,
				    anti_item
				) VALUES (
				    ?,?,?,?,?,?,?
				)""".trim(), TAB_SEVENZIP_ARCHIVE_ENTRIES, COL_ENTRY_ID))) {
			int i = 1;

			// ENTRY_ID
			if (entryId instanceof final Number num) {
				setLong(ps, i++, num.longValue());
			}
			else {
				setString(ps, i++, entryId.toString());
			}
			setString(ps, i++, row.methods); // METHODS
			setString(ps, i++, row.windowsAttributes); // WINDOWS_ATTRIBUTES
			setBoolean(ps, i++, row.hasCrc); // HAS_CRC
			setBoolean(ps, i++, row.hasStream); // HAS_STREAM
			setBoolean(ps, i++, row.emptyStream); // EMPTY_STREAM
			setBoolean(ps, i++, row.antiItem); // ANTI_ITEM

			enforceAutoCommitDisabled(ps);
			ps.executeUpdate();
		}

		return entryId;
	}

	private static boolean isArchiveCandidate(final Path path) {
		return Arrays.stream(ArchiveType.values()).anyMatch(e -> path.getFileName().toString().toUpperCase(Locale.ROOT).endsWith(e.getExtension().toUpperCase(Locale.ROOT)));
	}

	private static String normalizeFileSeparators(final String path) {
		return path.replace('\\', '/').replace(File.separatorChar, '/');
	}

	private String toStoredPath(final Path archivePath, final boolean parent) {
		var storedPath = archivePath;

		if (parent) {
			storedPath = storedPath.getParent();
			if (storedPath == null) {
				return null;
			}
		}

		if (PathMode.RELATIVE.equals(pathMode)) {
			if (basePath.equals(storedPath)) {
				return null;
			}
			return normalizeFileSeparators(basePath.relativize(storedPath).toString()) + '/';
		}

		if (PathMode.PARENT.equals(pathMode)) {
			var root = storedPath.getRoot();
			if (root != null) {
				storedPath = root.relativize(storedPath);
			}
		}

		return normalizeFileSeparators(storedPath.toString()) + '/';
	}

	private static Date dateOf(final FileTime fileTime) {
		return fileTime == null || fileTime.toMillis() <= -11644473600000L ? null : Date.from(fileTime.toInstant()); // 1601-01-01
	}

	private static Long bytesLength(final byte[] data) {
		return data == null ? null : (long) data.length;
	}

	private static long sumZipPackedSize(final Iterable<ZipArchiveEntry> entries) {
		long total = 0L;
		for (final var entry : entries) {
			final var size = entry.getCompressedSize();
			if (size >= 0) {
				total += size;
			}
		}
		return total;
	}

	private static long sumZipUnpackedSize(final Iterable<ZipArchiveEntry> entries) {
		long total = 0L;
		for (final var entry : entries) {
			final var size = entry.getSize();
			if (size >= 0) {
				total += size;
			}
		}
		return total;
	}

	private static long sumRarPackedSize(final Iterable<FileHeader> entries) {
		long total = 0L;
		for (final var entry : entries) {
			total += Math.max(entry.getFullPackSize(), 0L);
		}
		return total;
	}

	private static long sumRarUnpackedSize(final Iterable<FileHeader> entries) {
		long total = 0L;
		for (final var entry : entries) {
			total += Math.max(entry.getFullUnpackSize(), 0L);
		}
		return total;
	}

	private static long sumSevenZipUnpackedSize(final Iterable<SevenZArchiveEntry> entries) {
		long total = 0L;
		for (final var entry : entries) {
			if (entry.hasStream() && !entry.isDirectory() && entry.getSize() >= 0) {
				total += entry.getSize();
			}
		}
		return total;
	}

	private static boolean sevenZipIsEncrypted(final SevenZArchiveEntry entry) {
		if (entry.getContentMethods() != null) {
			for (final var configuration : entry.getContentMethods()) {
				if (SevenZMethod.AES256SHA256.equals(configuration.getMethod())) {
					return true;
				}
			}
		}
		return false;
	}

	private static String sevenZipMethods(final Iterable<? extends SevenZMethodConfiguration> methods) {
		if (methods == null) {
			return null;
		}
		final var joiner = new StringJoiner(" -> ");
		for (final var configuration : methods) {
			joiner.add(String.valueOf(configuration.getMethod()));
		}
		return joiner.length() == 0 ? null : joiner.toString();
	}

	private static void rollbackQuietly(final Connection connection) {
		try {
			connection.rollback();
		}
		catch (final SQLException e) {
			e.printStackTrace(System.err);
		}
	}

	private static void setString(final PreparedStatement ps, final int index, final String value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.VARCHAR);
		}
		else {
			ps.setString(index, value);
		}
	}

	private static void setClob(final PreparedStatement ps, final int index, final String value) throws SQLException {
		if (value == null || value.isBlank()) {
			ps.setNull(index, Types.CLOB);
		}
		else {
			try (final var reader = new StringReader(value) {
				@Override
				public void close() {}
			}) {
				ps.setClob(index, reader, value.length());
			}
		}
	}

	private static void setLong(final PreparedStatement ps, final int index, final Long value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.BIGINT);
		}
		else {
			ps.setLong(index, value);
		}
	}

	private static void setInt(final PreparedStatement ps, final int index, final Integer value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.INTEGER);
		}
		else {
			ps.setInt(index, value);
		}
	}

	private static void setBoolean(final PreparedStatement ps, final int index, final Boolean value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.SMALLINT);
		}
		else {
			ps.setInt(index, value ? 1 : 0);
		}
	}

	private static void setTimestamp(final PreparedStatement ps, final int index, final Date value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.TIMESTAMP);
		}
		else {
			ps.setTimestamp(index, new Timestamp(value.getTime()));
		}
	}

	private List<Path> scan() throws IOException {
		try (final var walk = Files.walk(basePath, recursive ? Short.MAX_VALUE : 1, followLinks ? new FileVisitOption[] { FileVisitOption.FOLLOW_LINKS } : new FileVisitOption[0])) {
			return walk.filter(Files::isRegularFile).filter(ArchiveScanner::isArchiveCandidate).sorted(Comparator.comparing(p -> {
				try {
					return p.toRealPath(followLinks ? new LinkOption[0] : new LinkOption[] { LinkOption.NOFOLLOW_LINKS }).toString();
				}
				catch (final IOException e) {
					throw new UncheckedIOException(e);
				}
			})).toList();
		}
	}

	private long purgeMissingArchives(final Iterable<Path> archivePaths, final Connection connection) throws SQLException, IOException {
		final var selectSql = String.format("SELECT %s,%s,%s,%s,%s FROM %s", COL_ARCHIVE_ID, COL_ARCHIVE_PATH, COL_ARCHIVE_NAME, COL_ARCHIVE_SIZE, COL_LAST_MODIFIED, TAB_ARCHIVES);

		final var deleteSql = List.of(
		// @formatter:off
				String.format("DELETE FROM %s WHERE %s IN (SELECT %s FROM %s WHERE %s=?)", TAB_ZIP_ARCHIVE_ENTRIES, COL_ENTRY_ID, COL_ENTRY_ID, TAB_ARCHIVE_ENTRIES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s IN (SELECT %s FROM %s WHERE %s=?)", TAB_RAR_ARCHIVE_ENTRIES, COL_ENTRY_ID, COL_ENTRY_ID, TAB_ARCHIVE_ENTRIES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s IN (SELECT %s FROM %s WHERE %s=?)", TAB_SEVENZIP_ARCHIVE_ENTRIES, COL_ENTRY_ID, COL_ENTRY_ID, TAB_ARCHIVE_ENTRIES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s=?", TAB_ARCHIVE_ENTRIES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s=?", TAB_ZIP_ARCHIVES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s=?", TAB_RAR_ARCHIVES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s=?", TAB_SEVENZIP_ARCHIVES, COL_ARCHIVE_ID),
				String.format("DELETE FROM %s WHERE %s=?", TAB_ARCHIVES, COL_ARCHIVE_ID)
		// @formatter:on
		);

		final var existingArchiveKeys = new HashSet<Triple<String, Long, Instant>>();

		for (final var archivePath : archivePaths) {
			existingArchiveKeys.add(Triple.ofNonNull(toStoredPath(archivePath, true) + archivePath.getFileName(), Files.size(archivePath), ignoreDate ? Instant.EPOCH : Files.getLastModifiedTime(archivePath).toInstant().truncatedTo(ChronoUnit.SECONDS)));
		}

		long count = 0;
		try (final var psSelect = connection.prepareStatement(selectSql); final var rs = psSelect.executeQuery()) {
			while (rs.next()) {
				final var storedKey = Triple.ofNonNull(rs.getString(COL_ARCHIVE_PATH) + rs.getString(COL_ARCHIVE_NAME), rs.getLong(COL_ARCHIVE_SIZE), ignoreDate ? Instant.EPOCH : rs.getTimestamp(COL_LAST_MODIFIED).toInstant().truncatedTo(ChronoUnit.SECONDS));

				if (!existingArchiveKeys.contains(storedKey)) {
					System.err.println(" - Removing orphan archive from database: " + storedKey);
					final var archiveId = rs.getObject(COL_ARCHIVE_ID);

					long affectedRows = 0;
					for (final var sql : deleteSql) {
						try (final var ps = connection.prepareStatement(sql)) {
							if (archiveId instanceof final Number num) {
								ps.setLong(1, num.longValue());
							}
							else {
								ps.setString(1, archiveId.toString());
							}
							System.err.printf("    * %s <== %s *** %s%n", sql, archiveId, storedKey);
							enforceAutoCommitDisabled(ps);
							affectedRows += ps.executeUpdate();
						}
					}
					if (affectedRows < 1) {
						throw new IllegalStateException("Delete failed: 0 rows affected");
					}

					count++;
				}
			}

			connection.commit();
		}
		catch (final SQLException e) {
			try {
				connection.rollback();
			}
			catch (final SQLException rollbackEx) {
				e.addSuppressed(rollbackEx);
			}
			throw e;
		}
		return count;
	}

	private static void enforceAutoCommitDisabled(final Statement stmt) throws SQLException {
		if (stmt.getConnection().getAutoCommit()) {
			throw new IllegalStateException("Auto-commit mode is enabled");
		}
	}

	private abstract static class ArchiveRow {
		String archivePath;
		String archiveName;
		Long archiveSize;
		Date lastModified;
		ArchiveType archiveFormat;
		String archiveComment;
		Integer entryCount;
		Long totalPackedSize;
		Long totalUnpackedSize;
	}

	private static final class RarArchiveRow extends ArchiveRow {
		String formatVersion;
		Boolean isSolid;
		Boolean isLocked;
		Boolean isProtected;
		Boolean isAv;
		Boolean isNewNumbering;
		Boolean isMultiVolume;
		Boolean isFirstVolume;
		Boolean isEncrypted;
		Boolean isPasswordProtected;
		Boolean hasArchiveComment;
		Long highPosAv;
		Long posAv;
		Long encryptVersion;
		Long recoveryDataSize;
		String flags;
	}

	private static final class ZipArchiveRow extends ArchiveRow {
		String zipEncoding;
		Long zipFirstLocalFileHeaderOffset;
	}

	private static final class SevenZipArchiveRow extends ArchiveRow {}

	private abstract static class EntryRow {
		Serializable archiveId;
		Integer entryIndex;
		String entryPath;
		String entryName;
		Boolean isDirectory;
		Boolean isSymbolicLink;
		Long uncompressedSize;
		Long compressedSize;
		String crc;
		Integer method;
		Date lastModified;
		Date lastAccess;
		Date creationTime;
		String comment;
		String platform;
		Long internalAttributes;
		Long externalAttributes;
		Long unixMode;
		Long dataOffset;
		Integer versionRequired;
		Boolean isEncrypted;
		Boolean isUnicode;
	}

	private static final class ZipEntryRow extends EntryRow {
		boolean flagDataDescriptor;
		String encryptionMethod;
		Long extraFieldCount;
		Long localExtraLength;
		Long centralExtraLength;
		Integer versionMadeBy;
		Long diskNumberStart;
	}

	private static final class RarEntryRow extends EntryRow {
		Integer recoverySectors;
		boolean isSolid;
		boolean isSplitBefore;
		boolean isSplitAfter;
		boolean isRar5Container;
		boolean isRar5Family;
		String hashType;
		String hashDigest;
	}

	private static final class SevenZipEntryRow extends EntryRow {
		String methods;
		String windowsAttributes;
		boolean hasCrc;
		boolean hasStream;
		boolean emptyStream;
		boolean antiItem;
	}

}
