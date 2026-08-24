package io.github.albertus82.archscan;

import java.util.Locale;

import picocli.CommandLine;
import picocli.CommandLine.ITypeConverter;

public enum PathMode {
	RELATIVE,
	PARENT,
	ABSOLUTE;
}

class PathModeConverter implements ITypeConverter<PathMode> {

	@Override
	public PathMode convert(final String value) {
		return switch (value.toUpperCase(Locale.ROOT)) {
		case "R", "RELATIVE" -> PathMode.RELATIVE;
		case "P", "PARENT" -> PathMode.PARENT;
		case "A", "ABSOLUTE" -> PathMode.ABSOLUTE;
		default -> throw new CommandLine.TypeConversionException("Invalid path mode '" + value + "'. Valid values are: relative (R), parent (P), absolute (A).");
		};
	}

}
