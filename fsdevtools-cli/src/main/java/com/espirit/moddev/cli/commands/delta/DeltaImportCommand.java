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
import com.espirit.moddev.cli.commands.ImportCommand;
import com.espirit.moddev.cli.results.DeltaImportResult;
import com.espirit.moddev.util.FileUtil;
import com.github.rvesse.airline.annotations.Command;
import com.github.rvesse.airline.annotations.Option;
import com.github.rvesse.airline.annotations.help.Examples;
import de.espirit.firstspirit.access.UserService;
import de.espirit.firstspirit.access.project.Project;
import de.espirit.firstspirit.access.store.BasicElementInfo;
import de.espirit.firstspirit.access.store.IDProvider;
import de.espirit.firstspirit.access.store.Store;
import de.espirit.firstspirit.access.store.StoreElement;
import de.espirit.firstspirit.agency.StoreAgent;
import de.espirit.firstspirit.storage.Revision;
import de.espirit.firstspirit.store.access.nexport.operations.ImportOperation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Command that imports only the elements whose files differ from a git revision. Connects to FirstSpirit only if there are any.
 */
@Command(name = "importDelta")
@Examples(
		examples = {
				"importDelta",
				"importDelta --since origin/master",
				"importDelta --since v1.4.0 --dryRun",
				"importDelta --since HEAD~3 -i \"Hotfix teaser\""
		},
		descriptions = {
				"Imports the elements with uncommitted or untracked changes",
				"Imports the elements changed on the current branch since it split from origin/master, including uncommitted changes",
				"Lists the elements that differ from the tag v1.4.0, without importing them",
				"Imports the changes of the last three commits and the working tree, with a revision comment"
		})
public class DeltaImportCommand extends ImportCommand {

	private static final Logger LOGGER = LoggerFactory.getLogger(DeltaImportCommand.class);
	private static final String TAB_SEQUENCE = "\t\t\t\t";

	@Option(name = "--since", title = "gitRevision",
			description = "Import the files that differ from this git revision: a commit, branch or tag, compared at the point where it and HEAD split "
					+ "(default = HEAD, which imports only uncommitted and untracked changes).")
	private String _since = "HEAD";

	@Option(name = "--dryRun",
			description = "Only list the elements that would be imported, and those that would be skipped because the server has elements below them that are missing locally.")
	private boolean _dryRun;

	@Option(name = "--keepTempDir",
			description = "Keep the temporary directory the changed elements are imported from, to inspect it.")
	private boolean _keepTempDir;

	private Changes _changes;

	@Description
	public static String getDescription() {
		return String.join("\n\r\n" + TAB_SEQUENCE,
				"Imports only the elements whose files differ from a git revision (--since, default HEAD), including uncommitted and untracked files. Requires git.",
				"Does not delete elements on the server. Skips elements that changed on the server since the last exportDelta, and elements whose import would"
						+ " delete elements on the server.",
				"Accepts the options of the import command.");
	}

	@Override
	public boolean needsContext() {
		// connect only if git reports changes; call() reports a failing git command
		try {
			return !changes().plan().isEmpty();
		} catch (final IOException | RuntimeException e) {
			return false;
		}
	}

	@Override
	public DeltaImportResult call() {
		Path tempDir = null;
		try {
			final Changes changes = changes();
			final Path syncDir = changes.syncDir();
			final DeltaImportDirectory plan = changes.plan();
			StoreAgent storeAgent = null;
			ImportOperation.Result importResult = null;
			final List<String> skipped = new ArrayList<>();
			final List<String> notes = new ArrayList<>();
			if (!plan.isEmpty()) {
				storeAgent = getContext().requireSpecialist(StoreAgent.TYPE);
				final Project project = getContext().getProject();
				final String server = getHost() + "-" + getPort();
				final DeltaExportState.Stored stored = DeltaExportState.read(DeltaExportState.stateFile(syncDir, server, project.getId()));
				// a release state export does not show unreleased changes, which the import would overwrite
				final DeltaExportState.Stored exported = stored == null || stored.releaseState() ? null : stored;
				final Path importStateFile = DeltaImportState.stateFile(syncDir, server, project.getId());
				final ServerChanges serverChanges = new ServerChanges(exported == null ? -1 : exported.revision(), DeltaImportState.read(importStateFile));
				if (exported == null) {
					notes.add("The sync directory has no exportDelta state of the current state for " + server + ", so changes made on the server since the last export"
							+ " are not detected and may be overwritten. Run exportDelta without --useReleaseState once to enable the check.");
				}
				tempDir = Files.createTempDirectory("fs-cli-import-delta-");
				plan.build(syncDir, tempDir, changes.deletedFiles());
				protectServerElements(project, storeAgent, syncDir, tempDir, plan, serverChanges, skipped);
				plan.pruneParents(tempDir);
				if (!_dryRun && !plan.isEmpty()) {
					LOGGER.info("Importing {} changed element(s) from '{}' via '{}'", plan.getUnits().size() + plan.getFolders().size() + plan.getWholeDirs().size(),
							syncDir, tempDir);
					importResult = createImportOperation().perform(getSynchronizationDirectory(tempDir.toString()));
					Bookkeeping.merge(tempDir, syncDir);
					recordImport(project, importResult, serverChanges, importStateFile);
				}
			}

			final DeltaImportResult result = new DeltaImportResult(_since, _dryRun, storeAgent, importResult);
			result.getSkipped().addAll(skipped);
			result.getRestoredFiles().addAll(plan.getRestoredFiles());
			result.getUnits().addAll(plan.getUnits());
			result.getFolders().addAll(plan.getFolders());
			result.getWholeDirs().addAll(plan.getWholeDirs());
			result.getDeletedElementDirs().addAll(plan.getDeletedElementDirs());
			result.getIgnoredFiles().addAll(plan.getIgnored());
			result.getNotes().addAll(notes);
			if (_keepTempDir && tempDir != null) {
				result.setTempDir(tempDir.toString());
			}
			return result;
		} catch (final Exception e) {
			return new DeltaImportResult(e);
		} finally {
			if (tempDir != null && !_keepTempDir) {
				try {
					FileUtil.deleteDirectory(tempDir);
				} catch (final Exception e) {
					LOGGER.warn("Could not delete the temporary import directory '{}'", tempDir, e);
				}
			}
		}
	}

	/**
	 * The files that differ from the git revision, and the elements they belong to. Read once, by {@link #needsContext()}.
	 */
	private record Changes(@NotNull Path syncDir, @NotNull DeltaImportDirectory.DeletedFileSource deletedFiles, @NotNull DeltaImportDirectory plan) {
	}

	@NotNull
	private Changes changes() throws IOException {
		if (_changes == null) {
			final Path syncDir = Paths.get(getSynchronizationDirectoryString()).toAbsolutePath().normalize();
			if (!Files.isDirectory(syncDir)) {
				throw new IllegalStateException("Sync directory '" + syncDir + "' does not exist.");
			}
			final GitChanges changes = GitChanges.detect(syncDir, _since);
			final DeltaImportDirectory.DeletedFileSource deletedFiles = path -> GitChanges.readFile(syncDir, changes.getBase(), path);
			_changes = new Changes(syncDir, deletedFiles, DeltaImportDirectory.plan(syncDir, changes, deletedFiles));
		}
		return _changes;
	}

	/**
	 * The revisions up to which the server state is known locally: the last export, and the last import of each element.
	 */
	private record ServerChanges(long exportRevision, @NotNull Map<String, Long> importRevisions) {

		/**
		 * Finds an element in a subtree that was changed on the server after the last export or import of it.
		 *
		 * @return a description of the element, or null if nothing changed
		 */
		@Nullable
		String findChange(@NotNull final StoreElement element, final boolean recursive) {
			if (exportRevision < 0) {
				return null;
			}
			if (element instanceof IDProvider provider && provider.getRevision() != null) {
				final long revision = provider.getRevision().getId();
				final long known = Math.max(exportRevision, importRevisions.getOrDefault(DeltaImportState.key(provider.getStore().getType(), provider.getId()), -1L));
				if (revision > known) {
					return "'" + ServerElements.displayName(provider) + "' (id " + provider.getId() + ") changed on the server in revision " + revision
							+ ", after revision " + known;
				}
			}
			if (recursive) {
				for (final StoreElement child : element.getChildren()) {
					final String change = findChange(child, true);
					if (change != null) {
						return change;
					}
				}
			}
			return null;
		}
	}

	/**
	 * Skips units and folders that were changed on the server since the last export, or whose import would delete elements on
	 * the server that are missing locally. Both are imported with everything below them, so everything below is checked.
	 */
	private static void protectServerElements(@NotNull final Project project, @NotNull final StoreAgent storeAgent, @NotNull final Path syncDir,
											  @NotNull final Path tempDir, @NotNull final DeltaImportDirectory plan,
											  @NotNull final ServerChanges serverChanges, @NotNull final List<String> skipped) throws IOException {
		final Function<Store.Type, Store> currentStores = type -> storeAgent.getStore(type, false);
		final Function<Store.Type, Store> exportedStores = exportedStores(project, serverChanges.exportRevision());
		final Set<Path> renamedDirs = new HashSet<>();
		plan.getRenamedDirs().forEach(dir -> renamedDirs.add(tempDir.resolve(dir)));
		final Set<String> skippedDirs = new TreeSet<>();
		final List<String> candidates = new ArrayList<>(plan.getUnits());
		candidates.addAll(plan.getFolders());
		for (final String dir : candidates) {
			final ServerElements.Resolution resolution = ServerElements.resolve(currentStores, syncDir, dir);
			final String reason;
			if (resolution.ambiguousAt() != null) {
				reason = "'" + resolution.ambiguousAt() + "' matches more than one element on the server";
			} else if (resolution.element() == null) {
				if (exportedStores == null || ServerElements.resolve(exportedStores, syncDir, dir).element() == null) {
					LOGGER.debug("'{}' does not exist on the server (yet) - nothing to protect", dir);
					continue;
				}
				reason = "it was deleted or moved on the server after revision " + serverChanges.exportRevision() + " - run exportDelta first";
			} else {
				final String change = serverChanges.findChange(resolution.element(), true);
				final List<String> missing = ServerElements.missingLocally(resolution.element(), tempDir.resolve(dir), renamedDirs);
				if (change != null) {
					reason = change + " - run exportDelta first";
				} else if (!missing.isEmpty()) {
					reason = "the server has " + missing.size() + " element(s) below it that are missing locally and would be deleted: "
							+ String.join(", ", missing.subList(0, Math.min(5, missing.size()))) + (missing.size() > 5 ? ", ..." : "");
				} else {
					continue;
				}
			}
			skip(tempDir, plan, dir, reason, skipped, skippedDirs);
		}
		// a folder is imported with AllChildren, so FirstSpirit would delete a skipped element below it; deepest folders first
		final List<String> folders = new ArrayList<>(plan.getFolders());
		Collections.reverse(folders);
		for (final String folder : folders) {
			final String below = skippedDirs.stream().filter(dir -> dir.startsWith(folder + "/")).findFirst().orElse(null);
			if (below != null) {
				skip(tempDir, plan, folder, "'" + below + "' below it is skipped", skipped, skippedDirs);
			}
		}
	}

	/**
	 * Gets the stores as of the last export, to tell an element deleted or moved on the server from a new local one.
	 */
	@Nullable
	private static Function<Store.Type, Store> exportedStores(@NotNull final Project project, final long exportRevision) {
		final Revision revision = exportRevision < 0 ? null : project.getRevision(exportRevision);
		if (revision == null) {
			return null;
		}
		final UserService userService = project.getUserService(revision);
		return type -> userService.getStore(type, false);
	}

	private static void skip(@NotNull final Path tempDir, @NotNull final DeltaImportDirectory plan, @NotNull final String dir, @NotNull final String reason,
							 @NotNull final List<String> skipped, @NotNull final Set<String> skippedDirs) throws IOException {
		skipped.add(dir + " - " + reason);
		skippedDirs.add(dir);
		for (final String entities : plan.demote(tempDir, dir)) {
			skipped.add(entities + " - its schema " + dir + " is skipped");
		}
	}

	/**
	 * Remembers the revision of the imported elements, so that the next importDelta does not take them for server changes.
	 */
	private static void recordImport(@NotNull final Project project, @NotNull final ImportOperation.Result importResult, @NotNull final ServerChanges serverChanges,
									 @NotNull final Path importStateFile) throws IOException {
		final long revision = project.getRevision(new Date()).getId();
		final Map<String, Long> revisions = new HashMap<>(serverChanges.importRevisions());
		for (final Set<BasicElementInfo> elements : List.of(importResult.getCreatedElements(), importResult.getUpdatedElements(), importResult.getMovedElements())) {
			for (final BasicElementInfo element : elements) {
				if (element.getStoreType() != null) {
					revisions.put(DeltaImportState.key(element.getStoreType(), element.getNodeId()), revision);
				}
			}
		}
		DeltaImportState.write(importStateFile, revisions, serverChanges.exportRevision());
	}

	@Override
	public boolean isCreatingProjectIfMissing() {
		return !_dryRun && super.isCreatingProjectIfMissing();
	}

	@Override
	public boolean isActivateProjectIfDeactivated() {
		return !_dryRun && super.isActivateProjectIfDeactivated();
	}
}
