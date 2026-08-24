package io.github.albertus82.archscan;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

public enum ArchiveType {

	ZIP("ZIP", "ZIP"),
	RAR("RAR", "RAR"),
	SEVENZIP("7Z", "7-Zip");

	private final String extension;
	private final String description;

	ArchiveType(final String extension, final String description) {
		this.extension = extension;
		this.description = description;
	}

	public String getExtension() {
		return extension;
	}

	public String getDescription() {
		return description;
	}

	public static ArchiveType fromPath(final Path archivePath) {
		final var upperCaseArchiveName = Objects.requireNonNull(archivePath, "archivePath must not be null").getFileName().toString().toUpperCase(Locale.ROOT);
		for (final var type : ArchiveType.values()) {
			if (upperCaseArchiveName.endsWith('.' + type.extension.toUpperCase(Locale.ROOT))) {
				return type;
			}
		}
		throw new IllegalArgumentException(String.valueOf(archivePath));
	}

}
