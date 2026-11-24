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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Stores the revision of the last delta export in {@code .FirstSpirit/fs-cli-delta-export_<server>_<projectId>.properties}.
 */
public final class DeltaExportState {

	public static final String META_DIR = ".FirstSpirit";
	private static final String STATE_FILE_PREFIX = "fs-cli-delta-export_";

	private static final String KEY_REVISION = "revision";
	private static final String KEY_REVISION_TIME = "revisionTime";
	private static final String KEY_PROJECT_ID = "projectId";
	private static final String KEY_PROJECT_NAME = "projectName";
	private static final String KEY_SERVER = "server";
	private static final String KEY_RELEASE = "releaseState";

	private DeltaExportState() {
	}

	/**
	 * A stored state.
	 *
	 * @param revision     the exported revision
	 * @param releaseState true if the release state was exported
	 */
	public record Stored(long revision, boolean releaseState) {
	}

	/**
	 * Gets the state file of a server and project.
	 *
	 * @param syncDir   the sync directory
	 * @param server    the server name
	 * @param projectId the project id
	 * @return the state file
	 */
	@NotNull
	public static Path stateFile(@NotNull final Path syncDir, @NotNull final String server, final long projectId) {
		return syncDir.resolve(META_DIR).resolve(STATE_FILE_PREFIX + sanitize(server) + "_" + projectId + ".properties");
	}

	/**
	 * Reads the state file.
	 *
	 * @param stateFile the state file
	 * @return the stored state, or null if the file does not exist
	 * @throws IOException if the file cannot be read or contains no valid revision
	 */
	@Nullable
	public static Stored read(@NotNull final Path stateFile) throws IOException {
		if (!Files.isRegularFile(stateFile)) {
			return null;
		}
		final Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(stateFile, StandardCharsets.UTF_8)) {
			properties.load(reader);
		}
		final String revision = properties.getProperty(KEY_REVISION);
		if (revision == null || revision.isBlank()) {
			throw new IOException("Delta export state file '" + stateFile + "' contains no '" + KEY_REVISION + "'. Delete it or pass --sinceRevision.");
		}
		try {
			return new Stored(Long.parseLong(revision.trim()), Boolean.parseBoolean(properties.getProperty(KEY_RELEASE, "false")));
		} catch (final NumberFormatException e) {
			throw new IOException("Delta export state file '" + stateFile + "' contains an invalid revision '" + revision + "'.", e);
		}
	}

	/**
	 * Writes the state file. The file has no timestamp and is only written if its content changes.
	 *
	 * @param stateFile    the state file
	 * @param revision     the exported revision
	 * @param revisionTime the time of the exported revision
	 * @param projectId    the project id
	 * @param projectName  the project name
	 * @param server       the server name
	 * @param releaseState true if the release state was exported
	 * @throws IOException if the file cannot be written
	 */
	public static void write(@NotNull final Path stateFile, final long revision, final long revisionTime, final long projectId,
					  @NotNull final String projectName, @NotNull final String server, final boolean releaseState) throws IOException {
		final Properties properties = new Properties();
		properties.setProperty(KEY_PROJECT_ID, Long.toString(projectId));
		properties.setProperty(KEY_PROJECT_NAME, projectName);
		properties.setProperty(KEY_RELEASE, Boolean.toString(releaseState));
		properties.setProperty(KEY_REVISION, Long.toString(revision));
		properties.setProperty(KEY_REVISION_TIME, Long.toString(revisionTime));
		properties.setProperty(KEY_SERVER, server);

		final StringWriter writer = new StringWriter();
		properties.store(writer, null);
		// sorted and without the timestamp comment Properties always writes, so that an unchanged state gives the same file
		final String content = "# Written by fs-cli exportDelta. The next exportDelta continues from this revision.\n"
				+ writer.toString().lines().filter(line -> !line.startsWith("#")).sorted().collect(Collectors.joining("\n", "", "\n"));
		if (Files.isRegularFile(stateFile) && Files.readString(stateFile, StandardCharsets.UTF_8).contentEquals(content)) {
			return;
		}
		writeAtomically(stateFile, content);
	}

	/**
	 * Writes a file next to its final place and moves it over the old one, so that an interrupted write cannot leave a broken file.
	 */
	static void writeAtomically(@NotNull final Path file, @NotNull final String content) throws IOException {
		Files.createDirectories(file.getParent());
		final Path temp = Files.createTempFile(file.getParent(), STATE_FILE_PREFIX, ".tmp");
		try {
			Files.writeString(temp, content, StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (final AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	@NotNull
	static String sanitize(@NotNull final String value) {
		return value.replaceAll("[^A-Za-z0-9.-]", "_");
	}
}
