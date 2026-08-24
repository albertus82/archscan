package io.github.albertus82.archscan;

import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.temporal.TemporalAccessor;

import picocli.CommandLine.IVersionProvider;

public class VersionProvider implements IVersionProvider {

	@Override
	public String[] getVersion() {
		return new String[] { "${COMMAND-FULL-NAME} " + BuildInfo.getProperty("project.version") + " (" + DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(getVersionTimestamp()) + ')' };
	}

	private static TemporalAccessor getVersionTimestamp() {
		return DateTimeFormatter.ISO_ZONED_DATE_TIME.parse(BuildInfo.getProperty("version.timestamp"));
	}

}
