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

import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the partial export flags ({@value #ALL_CHILDREN}, {@value #EXTRA_ELEMENT}) of an {@code FS_Info.txt} file.
 */
public final class FsInfoFlags {

	public static final String FILE_NAME = "FS_Info.txt";
	public static final String ALL_CHILDREN = "AllChildren: 1";
	public static final String EXTRA_ELEMENT = "ExtraElement: true";
	public static final String HIERARCHIC_ELEMENT = "HierarchicElement: true";

	private static final String ALL_CHILDREN_KEY = "AllChildren:";
	private static final String EXTRA_ELEMENT_KEY = "ExtraElement:";

	private FsInfoFlags() {
	}

	/**
	 * Checks if the content contains the {@value #ALL_CHILDREN} flag.
	 *
	 * @param content the file content
	 * @return true if the flag is set
	 */
	public static boolean hasAllChildren(@NotNull final String content) {
		return headerLines(content).stream().anyMatch(line -> line.trim().equals(ALL_CHILDREN));
	}

	/**
	 * Checks if the content contains the {@value #EXTRA_ELEMENT} flag.
	 *
	 * @param content the file content
	 * @return true if the flag is set
	 */
	public static boolean hasExtraElement(@NotNull final String content) {
		return headerLines(content).stream().anyMatch(line -> line.trim().equals(EXTRA_ELEMENT));
	}

	/**
	 * Checks if the content contains the {@value #HIERARCHIC_ELEMENT} flag, which FirstSpirit writes for folders.
	 *
	 * @param content the file content
	 * @return true if the flag is set
	 */
	public static boolean hasHierarchicElement(@NotNull final String content) {
		return headerLines(content).stream().anyMatch(line -> line.trim().equals(HIERARCHIC_ELEMENT));
	}

	/**
	 * Returns the content with exactly the given flags. Flags are inserted at their sorted position, all other lines stay unchanged.
	 *
	 * @param content      the file content
	 * @param allChildren  set the {@value #ALL_CHILDREN} flag
	 * @param extraElement set the {@value #EXTRA_ELEMENT} flag
	 * @return the new content
	 */
	@NotNull
	public static String withFlags(@NotNull final String content, final boolean allChildren, final boolean extraElement) {
		final String separator = content.contains("\r\n") ? "\r\n" : "\n";
		final boolean trailingSeparator = content.endsWith(separator);
		final String[] lines = content.split("\r?\n", -1);
		final int lineCount = trailingSeparator ? lines.length - 1 : lines.length;

		// the header ends at the first empty line
		int headerEnd = 0;
		while (headerEnd < lineCount && !lines[headerEnd].isEmpty()) {
			headerEnd++;
		}

		final List<String> header = new ArrayList<>();
		for (int i = 0; i < headerEnd; i++) {
			final String trimmed = lines[i].trim();
			if (!trimmed.startsWith(ALL_CHILDREN_KEY) && !trimmed.startsWith(EXTRA_ELEMENT_KEY)) {
				header.add(lines[i]);
			}
		}
		if (allChildren) {
			insertSorted(header, ALL_CHILDREN);
		}
		if (extraElement) {
			insertSorted(header, EXTRA_ELEMENT);
		}

		final List<String> result = new ArrayList<>(header);
		for (int i = headerEnd; i < lineCount; i++) {
			result.add(lines[i]);
		}
		final String joined = String.join(separator, result);
		return trailingSeparator ? joined + separator : joined;
	}

	private static void insertSorted(@NotNull final List<String> header, @NotNull final String line) {
		for (int i = 0; i < header.size(); i++) {
			if (header.get(i).compareTo(line) > 0) {
				header.add(i, line);
				return;
			}
		}
		header.add(line);
	}

	@NotNull
	private static List<String> headerLines(@NotNull final String content) {
		final List<String> header = new ArrayList<>();
		for (final String line : content.split("\r?\n", -1)) {
			if (line.isEmpty()) {
				break;
			}
			header.add(line);
		}
		return header;
	}
}
