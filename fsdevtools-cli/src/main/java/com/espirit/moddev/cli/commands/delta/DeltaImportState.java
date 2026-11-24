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

import de.espirit.firstspirit.access.store.Store;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The revisions at which {@code importDelta} last imported elements into a server, so that its own imports are not taken for
 * changes made on the server.
 * <p>
 * The state is kept in {@code .FirstSpirit/fs-cli-delta-import_<server>_<projectId>.properties}, one line per element:
 * {@code <store type>.<element id>=<revision>}.
 */
public final class DeltaImportState {

	private static final String STATE_FILE_PREFIX = "fs-cli-delta-import_";

	private DeltaImportState() {
	}

	/**
	 * Gets the state file of a server and project.
	 *
	 * @param syncDir   the sync directory
	 * @param server    the server, for example {@code localhost-8000}
	 * @param projectId the project id
	 * @return the file, which may not exist
	 */
	@NotNull
	public static Path stateFile(@NotNull final Path syncDir, @NotNull final String server, final long projectId) {
		return syncDir.resolve(DeltaExportState.META_DIR).resolve(STATE_FILE_PREFIX + DeltaExportState.sanitize(server) + "_" + projectId + ".properties");
	}

	/**
	 * Builds the key of an element.
	 *
	 * @param storeType the store type, for example {@code PAGESTORE}
	 * @param id        the element id
	 * @return the key
	 */
	@NotNull
	public static String key(@NotNull final Store.Type storeType, final long id) {
		return storeType + "." + id;
	}

	/**
	 * Reads the state.
	 *
	 * @param stateFile the state file
	 * @return the import revision by element key, empty if the file does not exist
	 * @throws IOException if the file cannot be read or contains an invalid revision
	 */
	@NotNull
	public static Map<String, Long> read(@NotNull final Path stateFile) throws IOException {
		final Map<String, Long> revisions = new HashMap<>();
		if (!Files.isRegularFile(stateFile)) {
			return revisions;
		}
		final Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		for (final String key : properties.stringPropertyNames()) {
			try {
				revisions.put(key, Long.parseLong(properties.getProperty(key).trim()));
			} catch (final NumberFormatException e) {
				throw new IOException("Delta import state file '" + stateFile + "' contains an invalid revision for '" + key + "'.", e);
			}
		}
		return revisions;
	}

	/**
	 * Writes the state, without the entries the last export already covers.
	 *
	 * @param stateFile      the state file
	 * @param revisions      the import revision by element key
	 * @param exportRevision the revision of the last export, or -1
	 * @throws IOException if the file cannot be written
	 */
	public static void write(@NotNull final Path stateFile, @NotNull final Map<String, Long> revisions, final long exportRevision) throws IOException {
		final Map<String, Long> sorted = new TreeMap<>();
		revisions.forEach((key, revision) -> {
			if (revision > exportRevision) {
				sorted.put(key, revision);
			}
		});
		final StringBuilder content = new StringBuilder("# Written by fs-cli importDelta.\n");
		sorted.forEach((key, revision) -> content.append(key).append('=').append(revision).append('\n'));
		DeltaExportState.writeAtomically(stateFile, content.toString());
	}
}
