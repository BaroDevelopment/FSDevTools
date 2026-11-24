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

package com.espirit.moddev.cli.results;

import de.espirit.firstspirit.agency.StoreAgent;
import de.espirit.firstspirit.store.access.nexport.operations.ImportOperation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialization of {@link com.espirit.moddev.cli.results.ImportResult} that can be used in conjunction with the importDelta command.
 */
public class DeltaImportResult extends ImportResult {

	private final String _since;
	private final boolean _dryRun;
	private final List<String> _units = new ArrayList<>();
	private final List<String> _folders = new ArrayList<>();
	private final List<String> _wholeDirs = new ArrayList<>();
	private final List<String> _deletedElementDirs = new ArrayList<>();
	private final List<String> _ignoredFiles = new ArrayList<>();
	private final List<String> _skipped = new ArrayList<>();
	private final List<String> _restoredFiles = new ArrayList<>();
	private final List<String> _notes = new ArrayList<>();
	private String _tempDir;

	/**
	 * Creates a new instance.
	 *
	 * @param since      the git revision the files were compared with
	 * @param dryRun     true if nothing was imported on purpose
	 * @param storeAgent used to log the import result
	 * @param result     the import result, or null if nothing was imported
	 */
	public DeltaImportResult(@NotNull final String since, final boolean dryRun, @Nullable final StoreAgent storeAgent,
							 @Nullable final ImportOperation.Result result) {
		super(storeAgent, result);
		_since = since;
		_dryRun = dryRun;
	}

	public DeltaImportResult(@NotNull final Exception exception) {
		super(exception);
		_since = null;
		_dryRun = false;
	}

	/**
	 * Elements imported with their children.
	 *
	 * @return the list
	 */
	@NotNull
	public List<String> getUnits() {
		return _units;
	}

	/**
	 * Folders whose own files changed, imported with their content.
	 *
	 * @return the list
	 */
	@NotNull
	public List<String> getFolders() {
		return _folders;
	}

	/**
	 * Folders that were imported completely (datasets, project settings).
	 *
	 * @return the list
	 */
	@NotNull
	public List<String> getWholeDirs() {
		return _wholeDirs;
	}

	/**
	 * Element folders deleted locally, but not on the server.
	 *
	 * @return the list
	 */
	@NotNull
	public List<String> getDeletedElementDirs() {
		return _deletedElementDirs;
	}

	@NotNull
	public List<String> getIgnoredFiles() {
		return _ignoredFiles;
	}

	/**
	 * Changed elements that were not imported, because they changed on the server or their import would delete elements there.
	 *
	 * @return the list
	 */
	@NotNull
	public List<String> getSkipped() {
		return _skipped;
	}

	/**
	 * Gets notes about the import, for example a check that could not be done.
	 *
	 * @return the notes
	 */
	@NotNull
	public List<String> getNotes() {
		return _notes;
	}

	/**
	 * Locally deleted files that were restored, so the elements stay on the server.
	 *
	 * @return the list
	 */
	@NotNull
	public List<String> getRestoredFiles() {
		return _restoredFiles;
	}

	/**
	 * The temporary import directory, if it was kept.
	 *
	 * @return the value
	 */
	@Nullable
	public String getTempDir() {
		return _tempDir;
	}

	public void setTempDir(@Nullable final String tempDir) {
		_tempDir = tempDir;
	}

	@Override
	public void log() {
		if (isError()) {
			LOGGER.error("Delta import not successful", exception);
			return;
		}
		LOGGER.info("Delta import of the files that differ from git revision '{}'", _since);
		if (_units.isEmpty() && _folders.isEmpty() && _wholeDirs.isEmpty()) {
			LOGGER.info(_skipped.isEmpty() ? "No changed elements found - nothing to import." : "Nothing left to import.");
		}
		for (final String note : _notes) {
			LOGGER.warn("Note: {}", note);
		}
		logList(_dryRun ? "Would import with children" : "Imported with children", _units);
		logList(_dryRun ? "Would import folders with their content" : "Imported folders with their content", _folders);
		logList(_dryRun ? "Would import completely" : "Imported completely", _wholeDirs);
		if (!_deletedElementDirs.isEmpty()) {
			LOGGER.warn("{} element folder(s) were deleted locally. importDelta does not delete elements on the server - delete them there if intended:",
					_deletedElementDirs.size());
			for (final String dir : _deletedElementDirs) {
				LOGGER.warn("  - {}", dir);
			}
		}
		if (!_skipped.isEmpty()) {
			LOGGER.warn("{} changed element(s) {} imported, because they changed on the server or their import would delete elements on the server. "
					+ "Run exportDelta, merge, then import again:", _skipped.size(), _dryRun ? "would not be" : "were not");
			for (final String entry : _skipped) {
				LOGGER.warn("  - {}", entry);
			}
		}
		if (!_restoredFiles.isEmpty()) {
			LOGGER.info("{} file(s) of locally deleted sub elements were kept on the server", _restoredFiles.size());
		}
		if (!_ignoredFiles.isEmpty()) {
			LOGGER.info("{} changed file(s) belong to no element and were ignored: {}", _ignoredFiles.size(), _ignoredFiles);
		}
		if (_tempDir != null) {
			LOGGER.info("Temporary import directory kept at '{}'", _tempDir);
		}
		if (_dryRun) {
			LOGGER.info("Dry run - nothing was imported.");
			return;
		}
		if (get() != null) {
			super.log();
		}
	}

	private static void logList(@NotNull final String label, @NotNull final List<String> entries) {
		if (entries.isEmpty()) {
			return;
		}
		LOGGER.info("{} ({}):", label, entries.size());
		for (final String entry : entries) {
			LOGGER.info("  - {}", entry);
		}
	}
}
