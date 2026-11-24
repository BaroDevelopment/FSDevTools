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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Moves the files of a partial export from an empty directory into the sync directory.
 * <p>
 * FirstSpirit reads every file of the target directory before an export, which takes seconds for a large sync directory
 * even if only one element is exported. Exporting into an empty directory and moving the files avoids that.
 */
public final class ExportedFiles {

	private ExportedFiles() {
	}

	/**
	 * Moves the exported files into the sync directory, replacing files with the same path, and merges the bookkeeping, see
	 * {@link Bookkeeping}.
	 *
	 * @param exportDir the directory the elements were exported into
	 * @param syncDir   the sync directory
	 * @return false if an exported folder belongs to another element in the sync directory; nothing was moved then
	 * @throws IOException if a file cannot be read or moved
	 */
	public static boolean moveInto(@NotNull final Path exportDir, @NotNull final Path syncDir) throws IOException {
		final Path metaDir = exportDir.resolve(DeltaExportState.META_DIR);
		final List<Path> paths;
		try (Stream<Path> walk = Files.walk(exportDir)) {
			paths = walk.filter(path -> !path.equals(exportDir) && !path.startsWith(metaDir)).sorted().toList();
		}
		for (final Path path : paths) {
			if (Files.isDirectory(path) && !belongsToSameElement(path, syncDir.resolve(exportDir.relativize(path).toString()))) {
				return false;
			}
		}
		for (final Path path : paths) {
			final Path target = syncDir.resolve(exportDir.relativize(path).toString());
			if (Files.isDirectory(path)) {
				Files.createDirectories(target);
			} else {
				Files.move(path, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		Bookkeeping.merge(exportDir, syncDir);
		return true;
	}

	/**
	 * FirstSpirit names a folder after the element and adds the id if a sibling has the same name. It only sees the export
	 * directory, so a folder name can clash with a folder of another element in the sync directory.
	 */
	private static boolean belongsToSameElement(@NotNull final Path exported, @NotNull final Path existing) throws IOException {
		final Path exportedXml = exported.resolve(SyncDirLayout.STORE_ELEMENT_XML);
		final Path existingXml = existing.resolve(SyncDirLayout.STORE_ELEMENT_XML);
		if (!Files.isRegularFile(exportedXml) || !Files.isRegularFile(existingXml)) {
			return true;
		}
		return Objects.equals(SyncDirLayout.readElementId(exportedXml), SyncDirLayout.readElementId(existingXml));
	}
}
