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

import com.espirit.moddev.cli.api.annotations.Description;
import com.espirit.moddev.cli.api.parsing.identifier.EntitiesIdentifier;
import com.espirit.moddev.cli.api.parsing.identifier.Identifier;
import com.espirit.moddev.cli.commands.PermissionsMode;
import com.espirit.moddev.cli.commands.SimpleCommand;
import com.espirit.moddev.cli.commands.export.AbstractExportCommand;
import com.espirit.moddev.cli.results.DeltaExportResult;
import com.espirit.moddev.util.FileUtil;
import com.github.rvesse.airline.annotations.Arguments;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;
import com.github.rvesse.airline.annotations.help.Examples;
import com.github.rvesse.airline.annotations.restrictions.AllowedRawValues;
import de.espirit.firstspirit.access.project.Project;
import de.espirit.firstspirit.access.store.IDProvider;
import de.espirit.firstspirit.agency.OperationAgent;
import de.espirit.firstspirit.agency.StoreAgent;
import de.espirit.firstspirit.io.FileHandle;
import de.espirit.firstspirit.io.FileSystem;
import de.espirit.firstspirit.storage.Revision;
import de.espirit.firstspirit.store.access.nexport.operations.ExportOperation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Command that exports only the changes since the last export. Uses a FirstSpirit context.
 */
@Command(name = "exportDelta")
@Examples(
		examples = {
				"exportDelta",
				"exportDelta --dryRun",
				"exportDelta --sinceRevision 4711",
				"exportDelta --sinceDate 2026-11-01",
				"exportDelta -- projectproperty:ALL"
		},
		descriptions = {
				"Exports the changes since the last exportDelta",
				"Lists what would be exported and deleted, without changing anything",
				"Exports the changes made after revision 4711",
				"Exports the changes made after 2026-11-01 00:00 (local time), for example the first time after a full export",
				"Exports the changes and, every time, all project properties"
		})
public class DeltaExportCommand extends SimpleCommand<DeltaExportResult> {

	private static final Logger LOGGER = LoggerFactory.getLogger(DeltaExportCommand.class);
	private static final String TAB_SEQUENCE = "\t\t\t\t";

	@Option(name = "--sinceRevision", title = "revision",
			description = "Export the changes made after this revision. Overrides the revision recorded by the last exportDelta.")
	private Long _sinceRevision;

	@Option(name = "--sinceDate", title = "date",
			description = "Export the changes made after this point in time, for example 2026-11-23, 2026-11-23T14:30 (local time) or 2026-11-23T14:30:00Z. "
					+ "Overrides the revision recorded by the last exportDelta.")
	private String _sinceDate;

	@Option(name = "--useReleaseState",
			description = "Export the release state of store elements (default = false, export of current state).")
	private boolean _releaseState;

	@Option(name = "--permissionMode", title = "permissionMode",
			description = "Set the permission mode for the export (default = NONE). Possible values are [NONE, ALL, STORE_ELEMENT, WORKFLOW].")
	@AllowedRawValues(ignoreCase = true, allowedValues = {"NONE", "ALL", "STORE_ELEMENT", "WORKFLOW"})
	private PermissionsMode _permissionMode = PermissionsMode.NONE;

	@Option(name = "--dryRun",
			description = "Only list what would be exported and deleted. Changes nothing and records no revision.")
	private boolean _dryRun;

	@Arguments(title = "identifiers",
			description = "Optional identifiers that are exported completely on every run, in addition to the changes, for example 'projectproperty:ALL'. "
					+ "Same syntax as for the export command.")
	private List<String> _identifiers = new ArrayList<>();

	/**
	 * The revision to start from.
	 *
	 * @param revision the revision id
	 * @param source   where the revision comes from, for logging
	 */
	private record Base(long revision, @NotNull String source) {
	}

	@Description
	public static String getDescription() {
		return String.join("\n\r\n" + TAB_SEQUENCE,
				"Exports only the elements and datasets that changed on the server since the last run into a sync directory that a full export filled before,"
						+ " and removes the folders of elements deleted, moved or renamed on the server.",
				"The revision is recorded in .FirstSpirit/fs-cli-delta-export_<host>-<port>_<project id>.properties. The first run needs --sinceDate or --sinceRevision.",
				"Project properties are not tracked by revisions: pass them as identifiers, for example 'projectproperty:ALL'.");
	}

	@Override
	public DeltaExportResult call() {
		try {
			final String syncDirStr = getSynchronizationDirectoryString();
			final Path syncDir = Paths.get(syncDirStr).toAbsolutePath().normalize();
			final Project project = getContext().getProject();
			final StoreAgent storeAgent = getContext().requireSpecialist(StoreAgent.TYPE);
			final List<Identifier> identifiers = parseIdentifiers();
			if (SyncDirLayout.storeDirs(syncDir).isEmpty()) {
				throw new IllegalStateException("'" + syncDir + "' contains no exported stores. Run a full export into it first.");
			}
			final String server = getHost() + "-" + getPort();
			final Path stateFile = DeltaExportState.stateFile(syncDir, server, project.getId());

			final Revision target = project.getRevision(new Date());
			final Base base = resolveBase(project, syncDir, stateFile);
			if (base.revision() > target.getId()) {
				throw new IllegalStateException("The base revision " + base.revision() + " (" + base.source() + ") is newer than the latest revision "
						+ target.getId() + " of project '" + project.getName() + "'. Was the sync directory exported from another server? Pass --sinceRevision.");
			}
			LOGGER.info("Collecting changes of project '{}' after revision {} ({}) up to revision {}", project.getName(), base.revision(), base.source(), target.getId());

			final RevisionChanges changes = RevisionChanges.collect(project, base.revision(), target, _releaseState);
			final DeltaExportPlan plan = DeltaExportPlan.create(project, storeAgent, syncDir, base.revision(), changes, _releaseState);

			final DeltaExportResult.Summary summary = new DeltaExportResult.Summary(base.revision(), base.source(), target.getId(), _dryRun);
			summary.setRevisionCount(changes.getRevisionCount());
			summary.setStoreAgent(storeAgent);
			summary.getRevisionsWithoutDetails().addAll(changes.getRevisionsWithoutDetails());
			plan.getUnits().forEach(unit -> summary.getUnits().add(DeltaExportPlan.describe(unit)));
			plan.getFolders().forEach(folder -> summary.getFolders().add(DeltaExportPlan.describe(folder)));
			summary.getEntitySources().addAll(plan.getEntitySources());
			summary.getIdentifiers().addAll(_identifiers);
			plan.getStaleDirs().forEach(dir -> summary.getDeletedDirs().add(syncDir.relativize(dir).toString()));
			summary.getNotes().addAll(plan.getNotes());
			if (_dryRun) {
				return new DeltaExportResult(summary);
			}

			if (!plan.isEmpty() || !identifiers.isEmpty()) {
				export(syncDir, storeAgent, plan, identifiers, summary);
			}
			DeltaExportState.write(stateFile, target.getId(), target.getCommitOrCreationTime(), project.getId(), project.getName(),
					server, _releaseState);
			return new DeltaExportResult(summary);
		} catch (final Exception e) {
			return new DeltaExportResult(e);
		}
	}

	/**
	 * Deletes the folders of stale and replaced elements and exports the plan. The folders are moved aside first and put back
	 * if the export fails, so that a failed export leaves the sync directory as it was, apart from new folders.
	 * <p>
	 * The elements are exported into an empty directory next to the sync directory and then moved into it, see
	 * {@link ExportedFiles}.
	 */
	private void export(@NotNull final Path syncDir, @NotNull final StoreAgent storeAgent, @NotNull final DeltaExportPlan plan,
						@NotNull final List<Identifier> identifiers, @NotNull final DeltaExportResult.Summary summary) throws IOException {
		final Map<Path, String> fsInfoSnapshot = SyncDirLayout.snapshotFsInfos(syncDir);
		final Path backupDir = Files.createTempDirectory(syncDir.getParent(), ".fs-cli-export-delta-");
		final Map<Path, Path> movedAside = new LinkedHashMap<>();
		boolean exported = false;
		try {
			final Set<Path> dirs = new LinkedHashSet<>(plan.getStaleDirs());
			dirs.addAll(plan.getReplacedDirs());
			for (final Path dir : dirs) {
				// a folder below another one is moved aside with it
				if (Files.exists(dir) && !DeltaExportPlan.isBelowAny(dir, dirs)) {
					final Path backup = backupDir.resolve(Integer.toString(movedAside.size()));
					Files.move(dir, backup);
					movedAside.put(dir, backup);
				}
			}
			final Path exportDir = Files.createDirectory(backupDir.resolve("export"));
			performExports(getSynchronizationDirectory(exportDir.toString()), storeAgent, plan, identifiers, summary);
			if (!ExportedFiles.moveInto(exportDir, syncDir)) {
				summary.getNotes().add("An exported folder name is taken by another element in the sync directory, so the elements were exported"
						+ " into the sync directory directly, which takes longer");
				summary.getExportResults().clear();
				performExports(getSynchronizationDirectory(syncDir.toString()), storeAgent, plan, identifiers, summary);
			}
			exported = true;
		} finally {
			if (!exported) {
				for (final Map.Entry<Path, Path> entry : movedAside.entrySet()) {
					FileUtil.deleteDirectory(entry.getKey());
					Files.move(entry.getValue(), entry.getKey());
				}
			}
			summary.setRestoredFsInfoFiles(SyncDirLayout.restoreFsInfoFlags(syncDir, fsInfoSnapshot));
			FileUtil.deleteDirectory(backupDir);
		}
	}

	private void performExports(@NotNull final FileSystem<FileHandle> fileSystem, @NotNull final StoreAgent storeAgent, @NotNull final DeltaExportPlan plan,
								@NotNull final List<Identifier> identifiers, @NotNull final DeltaExportResult.Summary summary) throws IOException {
		if (!plan.getUnits().isEmpty() || !plan.getEntitySources().isEmpty() || !identifiers.isEmpty()) {
			final ExportOperation operation = createExportOperation(true);
			for (final IDProvider unit : plan.getUnits()) {
				operation.addElement(unit);
			}
			for (final String content2 : plan.getEntitySources()) {
				new EntitiesIdentifier(content2).addToExportOperation(storeAgent, _releaseState, operation);
			}
			for (final Identifier identifier : identifiers) {
				identifier.addToExportOperation(storeAgent, _releaseState, operation);
			}
			summary.getExportResults().add(operation.perform(fileSystem));
		}
		if (!plan.getFolders().isEmpty()) {
			final ExportOperation operation = createExportOperation(false);
			for (final IDProvider folder : plan.getFolders()) {
				operation.addElement(folder);
			}
			summary.getExportResults().add(operation.perform(fileSystem));
		}
	}

	@NotNull
	private ExportOperation createExportOperation(final boolean withChildren) {
		final ExportOperation operation = getContext().requireSpecialist(OperationAgent.TYPE).getOperation(ExportOperation.TYPE);
		operation.configurePermissionTransport().setPermissionTransport(_permissionMode.getFirstSpiritPermissionMode());
		// FirstSpirit would delete every file that is not part of this export
		operation.setDeleteObsoleteFiles(false);
		operation.setExportChildElements(withChildren);
		operation.setExportParentElements(true);
		operation.setExportRelease(_releaseState);
		return operation;
	}

	@NotNull
	private Base resolveBase(@NotNull final Project project, @NotNull final Path syncDir, @NotNull final Path stateFile) throws IOException {
		if (_sinceRevision != null && _sinceDate != null) {
			throw new IllegalArgumentException("Pass either --sinceRevision or --sinceDate, not both.");
		}
		if (_sinceRevision != null) {
			if (_sinceRevision < 0) {
				throw new IllegalArgumentException("--sinceRevision must not be negative.");
			}
			return new Base(_sinceRevision, "--sinceRevision");
		}
		if (_sinceDate != null) {
			final Instant instant = parseDate(_sinceDate);
			return new Base(revisionAt(project, instant), "--sinceDate " + _sinceDate);
		}
		final DeltaExportState.Stored stored = DeltaExportState.read(stateFile);
		if (stored != null) {
			if (stored.releaseState() != _releaseState) {
				throw new IllegalStateException("The last exportDelta exported the " + (stored.releaseState() ? "release" : "current") + " state, this one exports the "
						+ (_releaseState ? "release" : "current") + " state. Pass --sinceRevision or --sinceDate to say where to start.");
			}
			return new Base(stored.revision(), "recorded in " + syncDir.relativize(stateFile));
		}
		// the export time FirstSpirit writes to .FirstSpirit/Import_*.txt is no starting point: every import rewrites it, too
		throw new IllegalStateException("The sync directory '" + syncDir + "' has no exportDelta state for this server and project yet. Pass --sinceDate with the "
				+ "time of the last full export into it, or --sinceRevision.");
	}

	private static long revisionAt(@NotNull final Project project, @NotNull final Instant instant) {
		final Revision revision = project.getRevision(Date.from(instant));
		if (revision == null) {
			throw new IllegalArgumentException("Project '" + project.getName() + "' has no revision at " + instant + ".");
		}
		return revision.getId();
	}

	/**
	 * Parses a date like 2026-11-23, 2026-11-23T14:30 (local time) or 2026-11-23T14:30:00Z.
	 *
	 * @param value the date
	 * @return the point in time
	 */
	@NotNull
	public static Instant parseDate(@NotNull final String value) {
		final String trimmed = value.trim();
		try {
			return Instant.parse(trimmed);
		} catch (final DateTimeParseException ignored) {
			// try the next format
		}
		try {
			return OffsetDateTime.parse(trimmed).toInstant();
		} catch (final DateTimeParseException ignored) {
			// try the next format
		}
		try {
			return LocalDateTime.parse(trimmed).atZone(ZoneId.systemDefault()).toInstant();
		} catch (final DateTimeParseException ignored) {
			// try the next format
		}
		try {
			return LocalDate.parse(trimmed).atStartOfDay(ZoneId.systemDefault()).toInstant();
		} catch (final DateTimeParseException e) {
			throw new IllegalArgumentException("Cannot parse --sinceDate '" + value + "'. Use for example 2026-11-23, 2026-11-23T14:30 or 2026-11-23T14:30:00Z.", e);
		}
	}

	@NotNull
	private List<Identifier> parseIdentifiers() {
		if (_identifiers.isEmpty()) {
			return List.of();
		}
		return AbstractExportCommand.createIdentifierParser().parse(_identifiers);
	}
}
