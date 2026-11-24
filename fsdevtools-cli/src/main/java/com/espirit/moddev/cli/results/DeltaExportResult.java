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

import com.espirit.moddev.cli.results.logging.AdvancedLogger;
import de.espirit.firstspirit.agency.StoreAgent;
import de.espirit.firstspirit.store.access.nexport.operations.ExportOperation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialization of {@link com.espirit.moddev.cli.results.SimpleResult} that can be used in conjunction with the exportDelta command.
 */
public class DeltaExportResult extends SimpleResult<DeltaExportResult.Summary> {

	/**
	 * What a delta export found and did.
	 */
	public static final class Summary {

		private final long _baseRevision;
		private final String _baseSource;
		private final long _targetRevision;
		private final boolean _dryRun;
		private final List<String> _units = new ArrayList<>();
		private final List<String> _folders = new ArrayList<>();
		private final List<String> _entitySources = new ArrayList<>();
		private final List<String> _identifiers = new ArrayList<>();
		private final List<String> _deletedDirs = new ArrayList<>();
		private final List<String> _notes = new ArrayList<>();
		private final List<Long> _revisionsWithoutDetails = new ArrayList<>();
		private final List<ExportOperation.Result> _exportResults = new ArrayList<>();
		private int _revisionCount;
		private int _restoredFsInfoFiles;
		@Nullable
		private StoreAgent _storeAgent;

		public Summary(final long baseRevision, @NotNull final String baseSource, final long targetRevision, final boolean dryRun) {
			_baseRevision = baseRevision;
			_baseSource = baseSource;
			_targetRevision = targetRevision;
			_dryRun = dryRun;
		}

		public long getBaseRevision() {
			return _baseRevision;
		}

		@NotNull
		public String getBaseSource() {
			return _baseSource;
		}

		public long getTargetRevision() {
			return _targetRevision;
		}

		public boolean isDryRun() {
			return _dryRun;
		}

		/**
		 * Elements exported with their children.
		 *
		 * @return the list
		 */
		@NotNull
		public List<String> getUnits() {
			return _units;
		}

		/**
		 * Folders exported without their children.
		 *
		 * @return the list
		 */
		@NotNull
		public List<String> getFolders() {
			return _folders;
		}

		/**
		 * Content sources whose datasets were exported.
		 *
		 * @return the list
		 */
		@NotNull
		public List<String> getEntitySources() {
			return _entitySources;
		}

		/**
		 * Identifiers passed on the command line, which are exported every time.
		 *
		 * @return the list
		 */
		@NotNull
		public List<String> getIdentifiers() {
			return _identifiers;
		}

		/**
		 * Deleted folders of deleted, moved or renamed elements.
		 *
		 * @return the list
		 */
		@NotNull
		public List<String> getDeletedDirs() {
			return _deletedDirs;
		}

		@NotNull
		public List<String> getNotes() {
			return _notes;
		}

		@NotNull
		public List<Long> getRevisionsWithoutDetails() {
			return _revisionsWithoutDetails;
		}

		@NotNull
		public List<ExportOperation.Result> getExportResults() {
			return _exportResults;
		}

		public int getRevisionCount() {
			return _revisionCount;
		}

		public void setRevisionCount(final int revisionCount) {
			_revisionCount = revisionCount;
		}

		public int getRestoredFsInfoFiles() {
			return _restoredFsInfoFiles;
		}

		public void setRestoredFsInfoFiles(final int restoredFsInfoFiles) {
			_restoredFsInfoFiles = restoredFsInfoFiles;
		}

		@Nullable
		public StoreAgent getStoreAgent() {
			return _storeAgent;
		}

		public void setStoreAgent(@Nullable final StoreAgent storeAgent) {
			_storeAgent = storeAgent;
		}

		public boolean isEmpty() {
			return _units.isEmpty() && _folders.isEmpty() && _entitySources.isEmpty() && _identifiers.isEmpty() && _deletedDirs.isEmpty();
		}
	}

	public DeltaExportResult(@NotNull final Summary summary) {
		super(summary);
	}

	public DeltaExportResult(@NotNull final Exception exception) {
		super(exception);
	}

	@Override
	public void log() {
		if (isError()) {
			LOGGER.error("Delta export not successful", exception);
			return;
		}
		final Summary summary = get();
		LOGGER.info("Delta export from revision {} ({}) to revision {}: {} revision(s) checked", summary.getBaseRevision(), summary.getBaseSource(),
				summary.getTargetRevision(), summary.getRevisionCount());
		if (summary.isEmpty()) {
			LOGGER.info("Nothing changed - the sync directory is up to date.");
		}
		logList(summary.isDryRun() ? "Would export with children" : "Exported with children", summary.getUnits());
		logList(summary.isDryRun() ? "Would export without children" : "Exported without children", summary.getFolders());
		logList(summary.isDryRun() ? "Would export datasets of" : "Exported datasets of", summary.getEntitySources());
		logList(summary.isDryRun() ? "Would export completely" : "Exported completely", summary.getIdentifiers());
		logList(summary.isDryRun() ? "Would delete folder" : "Deleted folder", summary.getDeletedDirs());
		for (final String note : summary.getNotes()) {
			LOGGER.info("Note: {}", note);
		}
		if (!summary.getRevisionsWithoutDetails().isEmpty()) {
			LOGGER.warn("{} revision(s) did not name their changed elements and were skipped: {}. If one of them changed content, export it with "
					+ "'exportDelta -- <identifier>' or run a full export.", summary.getRevisionsWithoutDetails().size(), summary.getRevisionsWithoutDetails());
		}
		if (summary.getRestoredFsInfoFiles() > 0) {
			LOGGER.debug("Restored the partial export markers of {} FS_Info.txt file(s)", summary.getRestoredFsInfoFiles());
		}
		if (summary.isDryRun()) {
			LOGGER.info("Dry run - nothing was exported, deleted or recorded.");
			return;
		}
		for (final ExportOperation.Result result : summary.getExportResults()) {
			AdvancedLogger.logExportResult(LOGGER, summary.getStoreAgent(), result);
		}
		LOGGER.info("Delta export successful");
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
