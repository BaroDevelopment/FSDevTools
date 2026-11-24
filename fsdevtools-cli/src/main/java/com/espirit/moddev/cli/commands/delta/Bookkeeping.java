/*
 *
 * *********************************************************************
 * fsdevtools
 * %%
 * Copyright (C) 2026 Crownpeak Technology GmbH
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * *********************************************************************
 *
 */


package com.espirit.moddev.cli.commands.delta;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * FirstSpirit's bookkeeping in {@code .FirstSpirit}: {@code Files_<host>_<project id>.txt} and {@code Import_<host>_<project id>.txt},
 * with one block per element. A full import uses them to skip unchanged elements.
 */
public final class Bookkeeping {

	private static final Pattern BLOCK_START = Pattern.compile("^\\[[^\\]\\r\\n]+]\\r?$", Pattern.MULTILINE);

	private Bookkeeping() {
	}

	/**
	 * Merges the bookkeeping FirstSpirit wrote for a partial export or import into the bookkeeping of the sync directory.
	 *
	 * @param partialDir the directory of the partial export or import
	 * @param syncDir    the sync directory
	 * @throws IOException if a file cannot be read or written
	 */
	public static void merge(@NotNull final Path partialDir, @NotNull final Path syncDir) throws IOException {
		final Path source = partialDir.resolve(DeltaExportState.META_DIR);
		if (!Files.isDirectory(source)) {
			return;
		}
		final List<Path> files;
		try (Stream<Path> list = Files.list(source)) {
			files = list.filter(Files::isRegularFile).filter(Bookkeeping::isBookkeeping).toList();
		}
		final Path target = Files.createDirectories(syncDir.resolve(DeltaExportState.META_DIR));
		for (final Path file : files) {
			final Path existing = target.resolve(file.getFileName().toString());
			// ISO-8859-1 keeps every byte as it is
			final String update = Files.readString(file, StandardCharsets.ISO_8859_1);
			final String merged = Files.isRegularFile(existing) ? mergeBlocks(Files.readString(existing, StandardCharsets.ISO_8859_1), update) : update;
			Files.writeString(existing, merged, StandardCharsets.ISO_8859_1);
		}
	}

	private static boolean isBookkeeping(@NotNull final Path file) {
		final String name = file.getFileName().toString();
		return name.startsWith("Files_") || name.startsWith("Import_");
	}

	/**
	 * Merges two bookkeeping files: a header followed by blocks that start with {@code [<element id>]}. The header and the
	 * blocks of the update replace those of the existing file, the other blocks stay.
	 *
	 * @param existing the content of the file in the sync directory
	 * @param update   the content written by the partial export or import
	 * @return the merged content
	 */
	@NotNull
	static String mergeBlocks(@NotNull final String existing, @NotNull final String update) {
		final Map<String, String> blocks = new LinkedHashMap<>();
		splitBlocks(existing, blocks);
		final String header = splitBlocks(update, blocks);
		final StringBuilder merged = new StringBuilder(header);
		// FirstSpirit separates the blocks by an empty line
		for (final String block : blocks.values()) {
			merged.append(block.stripTrailing()).append("\n\n");
		}
		return merged.toString();
	}

	/**
	 * Adds the blocks of a file to the map, replacing blocks with the same key.
	 *
	 * @return the header
	 */
	@NotNull
	private static String splitBlocks(@NotNull final String content, @NotNull final Map<String, String> blocks) {
		final Matcher matcher = BLOCK_START.matcher(content);
		final List<Integer> starts = new ArrayList<>();
		while (matcher.find()) {
			starts.add(matcher.start());
		}
		if (starts.isEmpty()) {
			return content;
		}
		for (int i = 0; i < starts.size(); i++) {
			final int end = i + 1 < starts.size() ? starts.get(i + 1) : content.length();
			final String block = content.substring(starts.get(i), end);
			final int lineEnd = block.indexOf('\n');
			blocks.put((lineEnd < 0 ? block : block.substring(0, lineEnd)).trim(), block);
		}
		return content.substring(0, starts.get(0));
	}
}
