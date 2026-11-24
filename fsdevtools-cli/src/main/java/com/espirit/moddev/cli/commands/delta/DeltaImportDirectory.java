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

import com.espirit.moddev.util.FileUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds a temporary sync directory with only the changed elements, in the format of a FirstSpirit partial export.
 * <ul>
 *     <li>Units (a page, template, media, ...) are copied with their children.</li>
 *     <li>Folders are copied with everything below them: FirstSpirit cannot import a folder without its children.</li>
 *     <li>Parents are copied as {@value FsInfoFlags#EXTRA_ELEMENT}, so FirstSpirit does not update them.</li>
 *     <li>Datasets are copied with their schema.</li>
 * </ul>
 * Nothing is deleted on the server.
 */
public final class DeltaImportDirectory {

	/**
	 * Supplies the old content of a deleted file.
	 */
	@FunctionalInterface
	public interface DeletedFileSource {

		/**
		 * Reads a deleted file.
		 *
		 * @param path the file, relative to the sync directory
		 * @return the old content
		 * @throws IOException if the file cannot be read
		 */
		@NotNull
		byte[] read(@NotNull String path) throws IOException;
	}

	private static final String SCHEMES_DIR = "TemplateStore/Schemes";

	private final Set<String> _units = new TreeSet<>();
	private final Set<String> _folders = new TreeSet<>();
	private final Set<String> _wholeDirs = new TreeSet<>();
	private final Set<String> _restoredFiles = new TreeSet<>();
	private final Set<String> _deletedElementDirs = new TreeSet<>();
	private final Set<String> _ignored = new TreeSet<>();
	private final Set<String> _renamedDirs = new TreeSet<>();
	private final Map<String, Set<String>> _entitiesBySchema = new HashMap<>();
	private final Map<String, Optional<String>> _schemaDirs = new HashMap<>();

	private DeltaImportDirectory() {
	}

	/**
	 * Finds the elements the changed files belong to.
	 *
	 * @param syncDir      the sync directory
	 * @param changes      the changed files
	 * @param deletedFiles supplies locally deleted files, used to tell a moved or renamed element from a deleted one
	 * @return the plan
	 * @throws IOException if a file cannot be read
	 */
	@NotNull
	public static DeltaImportDirectory plan(@NotNull final Path syncDir, @NotNull final GitChanges changes, @NotNull final DeletedFileSource deletedFiles)
			throws IOException {
		final DeltaImportDirectory plan = new DeltaImportDirectory();
		final Map<String, String> currentDirs = new HashMap<>();
		for (final String path : changes.getChanged()) {
			plan.addChanged(syncDir, path);
			if (fileNameOf(path).equals(SyncDirLayout.STORE_ELEMENT_XML)) {
				final String id = SyncDirLayout.readElementId(syncDir.resolve(path));
				if (id != null) {
					currentDirs.put(id, parentOf(path));
				}
			}
		}
		final Map<String, Boolean> movedDirs = new HashMap<>();
		for (final String path : changes.getDeleted()) {
			final String dir = parentOf(path);
			if (!dir.isEmpty() && !Files.exists(syncDir.resolve(dir)) && !movedDirs.containsKey(dir)) {
				final String newDir = newDirOf(dir, changes.getDeleted(), deletedFiles, currentDirs);
				movedDirs.put(dir, newDir != null);
				if (newDir != null && parentOf(newDir).equals(parentOf(dir))) {
					plan._renamedDirs.add(dir);
				}
			}
			if (!movedDirs.getOrDefault(dir, false)) {
				plan.addDeleted(syncDir, path);
			}
		}
		plan._units.removeIf(dir -> hasAncestorIn(dir, plan._units));
		plan._folders.removeIf(dir -> plan._units.contains(dir) || hasAncestorIn(dir, plan._units));
		plan._restoredFiles.removeIf(path -> !hasAncestorIn(path, plan._units));
		plan._deletedElementDirs.removeIf(dir -> hasAncestorIn(dir, plan._deletedElementDirs));
		return plan;
	}

	/**
	 * Git reports a moved or renamed element as deleted at its old place. It is recognized by the id in its
	 * {@code StoreElement.xml}, which the element keeps.
	 *
	 * @return the new folder of the element, or null if it was deleted
	 */
	@Nullable
	private static String newDirOf(@NotNull final String dir, @NotNull final Set<String> deleted, @NotNull final DeletedFileSource deletedFiles,
								   @NotNull final Map<String, String> currentDirs) throws IOException {
		final String storeElementXml = dir + "/" + SyncDirLayout.STORE_ELEMENT_XML;
		if (!deleted.contains(storeElementXml)) {
			return null;
		}
		final String id = SyncDirLayout.readElementId(deletedFiles.read(storeElementXml));
		return id == null ? null : currentDirs.get(id);
	}

	private void addChanged(@NotNull final Path syncDir, @NotNull final String path) {
		if (isMetadata(path)) {
			return;
		}
		final String[] segments = path.split("/");
		if (segments[0].equals(SyncDirLayout.GLOBAL_DIR)) {
			addGlobal(syncDir, path, segments);
			return;
		}
		if (segments[0].equals(SyncDirLayout.ENTITIES_DIR)) {
			addEntities(syncDir, path, segments);
			return;
		}
		if (!Files.isRegularFile(syncDir.resolve(path))) {
			_ignored.add(path);
			return;
		}
		final String elementDir = elementDirOf(syncDir, parentOf(path));
		if (elementDir == null) {
			_ignored.add(path);
		} else {
			addElement(syncDir, elementDir);
		}
	}

	private void addDeleted(@NotNull final Path syncDir, @NotNull final String path) {
		if (isMetadata(path)) {
			return;
		}
		final String[] segments = path.split("/");
		if (segments[0].equals(SyncDirLayout.GLOBAL_DIR)) {
			addGlobal(syncDir, path, segments);
			return;
		}
		if (segments[0].equals(SyncDirLayout.ENTITIES_DIR)) {
			if (segments.length > 3 && Files.isDirectory(syncDir.resolve(entitiesDir(segments)))) {
				addEntities(syncDir, path, segments);
			} else if (segments.length > 3) {
				_deletedElementDirs.add(entitiesDir(segments));
			}
			return;
		}
		final String parent = parentOf(path);
		if (parent.isEmpty()) {
			return;
		}
		if (SyncDirLayout.isElementDir(syncDir.resolve(parent))) {
			// a file of an existing element was removed, for example the data of a language
			addElement(syncDir, parent);
			return;
		}
		// the element folder itself is gone
		_deletedElementDirs.add(parent);
		final String existing = elementDirOf(syncDir, parentOf(parent));
		if (existing != null && !isHierarchic(syncDir, existing)) {
			// part of a unit, for example a section: import the unit and keep the element on the server
			_units.add(unitOf(syncDir, existing));
			_restoredFiles.add(path);
		}
	}

	private void addElement(@NotNull final Path syncDir, @NotNull final String elementDir) {
		if (isHierarchic(syncDir, elementDir)) {
			_folders.add(elementDir);
		} else {
			_units.add(unitOf(syncDir, elementDir));
		}
	}

	/**
	 * Project settings are imported per category ({@code Global/Project}, {@code Global/Schedule}, ...), so that a changed
	 * setting does not overwrite the other categories on the server.
	 */
	private void addGlobal(@NotNull final Path syncDir, @NotNull final String path, @NotNull final String[] segments) {
		final String category = segments.length > 2 ? SyncDirLayout.GLOBAL_DIR + "/" + segments[1] : null;
		if (category != null && Files.isDirectory(syncDir.resolve(category))) {
			_wholeDirs.add(category);
		} else if (segments.length > 1) {
			_ignored.add(path);
		}
	}

	private void addEntities(@NotNull final Path syncDir, @NotNull final String path, @NotNull final String[] segments) {
		if (segments.length <= 3) {
			_ignored.add(path);
			return;
		}
		final String schemaDir = _schemaDirs.computeIfAbsent(segments[1], name -> Optional.ofNullable(schemaDirOf(syncDir, name))).orElse(null);
		if (schemaDir == null) {
			_ignored.add(path);
			return;
		}
		_wholeDirs.add(entitiesDir(segments));
		_units.add(schemaDir);
		_entitiesBySchema.computeIfAbsent(schemaDir, key -> new TreeSet<>()).add(entitiesDir(segments));
	}

	/**
	 * Copies the planned elements into the empty target directory.
	 *
	 * @param syncDir      the sync directory
	 * @param target       the directory to import from
	 * @param deletedFiles supplies locally deleted files, which are restored so they stay on the server
	 * @throws IOException if a file cannot be copied
	 */
	public void build(@NotNull final Path syncDir, @NotNull final Path target, @NotNull final DeletedFileSource deletedFiles) throws IOException {
		final Set<String> extraDirs = new LinkedHashSet<>();
		for (final String dir : _units) {
			copyRecursively(syncDir.resolve(dir), target.resolve(dir));
			collectAncestors(dir, extraDirs);
		}
		for (final String path : _restoredFiles) {
			final Path file = target.resolve(path);
			Files.createDirectories(file.getParent());
			Files.write(file, deletedFiles.read(path));
		}
		// the old content would overwrite a newer one on the server: keep the element, but do not update it
		for (final String path : _restoredFiles) {
			markAsParent(target.resolve(parentOf(path)));
		}
		for (final String dir : _folders) {
			copyRecursively(syncDir.resolve(dir), target.resolve(dir));
			collectAncestors(dir, extraDirs);
		}
		for (final String dir : _wholeDirs) {
			copyRecursively(syncDir.resolve(dir), target.resolve(dir));
			collectAncestors(dir, extraDirs);
		}
		extraDirs.removeAll(_units);
		extraDirs.removeAll(_folders);
		extraDirs.removeIf(dir -> hasAncestorIn(dir, _units) || hasAncestorIn(dir, _folders) || hasAncestorIn(dir, _wholeDirs));
		for (final String dir : extraDirs) {
			copyParentFiles(syncDir.resolve(dir), target.resolve(dir));
			if (SyncDirLayout.isElementDir(syncDir.resolve(dir))) {
				markAsParent(target.resolve(dir));
			}
		}
	}

	/**
	 * Takes a planned unit or folder out of the import. A unit is removed from the target directory. A folder becomes a parent
	 * of the planned elements below it, which {@link #pruneParents(Path)} removes if nothing below it is imported.
	 *
	 * @param target the directory to import from
	 * @param dir    the unit or folder
	 * @return the dataset folders that are not imported either, because they need the schema {@code dir}
	 * @throws IOException if a file cannot be changed
	 */
	@NotNull
	public List<String> demote(@NotNull final Path target, @NotNull final String dir) throws IOException {
		final List<String> droppedEntities = new ArrayList<>();
		if (_units.remove(dir)) {
			// not left as a parent-only stub: FirstSpirit imports a stub without sub folders as an element without content
			FileUtil.deleteDirectory(target.resolve(dir));
			_restoredFiles.removeIf(path -> path.startsWith(dir + "/"));
			for (final String entities : _entitiesBySchema.getOrDefault(dir, Set.of())) {
				if (_wholeDirs.remove(entities)) {
					FileUtil.deleteDirectory(target.resolve(entities));
					droppedEntities.add(entities);
				}
			}
			return droppedEntities;
		}
		if (_folders.remove(dir)) {
			reduceToParent(target, dir);
		}
		return droppedEntities;
	}

	/**
	 * Turns a copied folder into a parent: only its own metadata stays, marked as {@value FsInfoFlags#EXTRA_ELEMENT}, and of
	 * its sub folders only those with planned elements.
	 */
	private void reduceToParent(@NotNull final Path target, @NotNull final String dir) throws IOException {
		final Path path = target.resolve(dir);
		final List<Path> children;
		try (Stream<Path> list = Files.list(path)) {
			children = list.toList();
		}
		for (final Path child : children) {
			final String name = child.getFileName().toString();
			final String childDir = dir + "/" + name;
			if (Files.isDirectory(child)) {
				if (_units.contains(childDir) || _folders.contains(childDir) || _wholeDirs.contains(childDir)) {
					continue;
				}
				if (isPlannedBelow(childDir)) {
					reduceToParent(target, childDir);
				} else {
					FileUtil.deleteDirectory(child);
				}
			} else if (!isParentFile(name)) {
				Files.delete(child);
			}
		}
		markAsParent(path);
	}

	private boolean isPlannedBelow(@NotNull final String dir) {
		final String prefix = dir + "/";
		return Stream.of(_units, _folders, _wholeDirs).flatMap(Set::stream).anyMatch(planned -> planned.startsWith(prefix));
	}

	private static boolean isParentFile(@NotNull final String name) {
		return name.equals(SyncDirLayout.STORE_ELEMENT_XML) || (name.startsWith("FS_") && name.endsWith(".txt"));
	}

	/**
	 * Removes parent-only folders that have nothing to import below them, so that FirstSpirit does not treat them as elements.
	 *
	 * @param target the directory to import from
	 * @throws IOException if a folder cannot be read or deleted
	 */
	public void pruneParents(@NotNull final Path target) throws IOException {
		final List<Path> dirs;
		try (Stream<Path> paths = Files.walk(target)) {
			dirs = paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList();
		}
		// a restored element is marked as parent, too, but has to stay, or FirstSpirit deletes it
		final Set<Path> restoredDirs = new HashSet<>();
		_restoredFiles.forEach(path -> restoredDirs.add(target.resolve(parentOf(path))));
		for (final Path dir : dirs) {
			if (!dir.equals(target) && !restoredDirs.contains(dir) && isParentOnly(dir) && !hasElementBelow(dir)) {
				FileUtil.deleteDirectory(dir);
			}
		}
	}

	private static boolean isParentOnly(@NotNull final Path dir) throws IOException {
		final Path fsInfo = dir.resolve(FsInfoFlags.FILE_NAME);
		return Files.isRegularFile(fsInfo) && FsInfoFlags.hasExtraElement(Files.readString(fsInfo, StandardCharsets.UTF_8));
	}

	private static boolean hasElementBelow(@NotNull final Path dir) throws IOException {
		try (Stream<Path> children = Files.list(dir)) {
			return children.anyMatch(Files::isDirectory);
		}
	}

	/**
	 * Copies the files FirstSpirit writes for a parent element.
	 */
	private static void copyParentFiles(@NotNull final Path source, @NotNull final Path destination) throws IOException {
		Files.createDirectories(destination);
		try (Stream<Path> children = Files.list(source)) {
			for (final Path child : (Iterable<Path>) children::iterator) {
				final String name = child.getFileName().toString();
				if (isParentFile(name) && Files.isRegularFile(child)) {
					Files.copy(child, destination.resolve(name), StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	private static void copyRecursively(@NotNull final Path source, @NotNull final Path destination) throws IOException {
		try (Stream<Path> paths = Files.walk(source)) {
			for (final Path path : (Iterable<Path>) paths::iterator) {
				final Path copy = destination.resolve(source.relativize(path).toString());
				if (Files.isDirectory(path)) {
					Files.createDirectories(copy);
				} else if (Files.isRegularFile(path)) {
					Files.copy(path, copy, StandardCopyOption.REPLACE_EXISTING);
				}
			}
		}
	}

	/**
	 * Marks an element as {@value FsInfoFlags#EXTRA_ELEMENT} without {@code AllChildren}, so FirstSpirit neither updates it nor
	 * deletes its children.
	 */
	private static void markAsParent(@NotNull final Path dir) throws IOException {
		final Path fsInfo = dir.resolve(FsInfoFlags.FILE_NAME);
		if (!Files.isRegularFile(fsInfo)) {
			return;
		}
		final String content = Files.readString(fsInfo, StandardCharsets.UTF_8);
		final String fixed = FsInfoFlags.withFlags(content, false, true);
		if (!fixed.equals(content)) {
			Files.writeString(fsInfo, fixed, StandardCharsets.UTF_8);
		}
	}

	/**
	 * Checks if an element is a folder. Other elements belong to a unit, for example a section to its page.
	 */
	private static boolean isHierarchic(@NotNull final Path syncDir, @NotNull final String elementDir) {
		final Path fsInfo = syncDir.resolve(elementDir).resolve(FsInfoFlags.FILE_NAME);
		try {
			return Files.isRegularFile(fsInfo) && FsInfoFlags.hasHierarchicElement(Files.readString(fsInfo, StandardCharsets.UTF_8));
		} catch (final IOException e) {
			return false;
		}
	}

	/**
	 * Gets the unit of an element, for example the page of a section.
	 */
	@NotNull
	private static String unitOf(@NotNull final Path syncDir, @NotNull final String elementDir) {
		String unit = elementDir;
		String parent = parentOf(unit);
		while (!parent.isEmpty() && SyncDirLayout.isElementDir(syncDir.resolve(parent)) && !isHierarchic(syncDir, parent)) {
			unit = parent;
			parent = parentOf(unit);
		}
		return unit;
	}

	/**
	 * Finds the folder of a schema below {@code TemplateStore/Schemes}.
	 */
	@Nullable
	private static String schemaDirOf(@NotNull final Path syncDir, @NotNull final String schemaName) {
		final Path schemes = syncDir.resolve(SCHEMES_DIR);
		if (!Files.isDirectory(schemes)) {
			return null;
		}
		final String wanted = SyncDirLayout.normalize(schemaName);
		try (Stream<Path> paths = Files.walk(schemes)) {
			final List<Path> matches = paths.filter(Files::isDirectory)
					.filter(dir -> SyncDirLayout.normalize(dir.getFileName().toString()).equalsIgnoreCase(wanted))
					.filter(DeltaImportDirectory::isSchema)
					.toList();
			return matches.size() == 1 ? syncDir.relativize(matches.get(0)).toString().replace('\\', '/') : null;
		} catch (final IOException e) {
			return null;
		}
	}

	private static boolean isSchema(@NotNull final Path dir) {
		try {
			return "SCHEMA".equals(SyncDirLayout.readRootTag(dir.resolve(SyncDirLayout.STORE_ELEMENT_XML)));
		} catch (final IOException e) {
			return false;
		}
	}

	@Nullable
	private static String elementDirOf(@NotNull final Path syncDir, @NotNull final String dir) {
		String current = dir;
		while (!current.isEmpty()) {
			if (SyncDirLayout.isElementDir(syncDir.resolve(current))) {
				return current;
			}
			current = parentOf(current);
		}
		return null;
	}

	@NotNull
	private static String entitiesDir(@NotNull final String[] segments) {
		return segments[0] + "/" + segments[1] + "/" + segments[2];
	}

	private static void collectAncestors(@NotNull final String dir, @NotNull final Set<String> ancestors) {
		String parent = parentOf(dir);
		while (!parent.isEmpty()) {
			ancestors.add(parent);
			parent = parentOf(parent);
		}
	}

	private static boolean isMetadata(@NotNull final String path) {
		return path.equals(DeltaExportState.META_DIR) || path.startsWith(DeltaExportState.META_DIR + "/");
	}

	private static boolean hasAncestorIn(@NotNull final String path, @NotNull final Set<String> dirs) {
		String parent = parentOf(path);
		while (!parent.isEmpty()) {
			if (dirs.contains(parent)) {
				return true;
			}
			parent = parentOf(parent);
		}
		return false;
	}

	@NotNull
	private static String fileNameOf(@NotNull final String path) {
		return path.substring(path.lastIndexOf('/') + 1);
	}

	@NotNull
	private static String parentOf(@NotNull final String path) {
		final int slash = path.lastIndexOf('/');
		return slash < 0 ? "" : path.substring(0, slash);
	}

	/**
	 * Gets the elements to import with their children.
	 *
	 * @return the folders of the units
	 */
	@NotNull
	public Set<String> getUnits() {
		return Collections.unmodifiableSet(_units);
	}

	/**
	 * Gets the folders whose own files changed. They are imported with everything below them.
	 *
	 * @return the folders
	 */
	@NotNull
	public Set<String> getFolders() {
		return Collections.unmodifiableSet(_folders);
	}

	/**
	 * Gets the folders to import completely (datasets, project settings).
	 *
	 * @return the folders
	 */
	@NotNull
	public Set<String> getWholeDirs() {
		return Collections.unmodifiableSet(_wholeDirs);
	}

	/**
	 * Gets the elements deleted locally. They are not deleted on the server.
	 *
	 * @return the folders
	 */
	@NotNull
	public Set<String> getDeletedElementDirs() {
		return Collections.unmodifiableSet(_deletedElementDirs);
	}

	/**
	 * Gets the old folders of elements that were renamed locally, in the same parent folder.
	 *
	 * @return the folders
	 */
	@NotNull
	public Set<String> getRenamedDirs() {
		return Collections.unmodifiableSet(_renamedDirs);
	}

	/**
	 * Gets the locally deleted files that are restored, so the elements stay on the server.
	 *
	 * @return the files
	 */
	@NotNull
	public Set<String> getRestoredFiles() {
		return Collections.unmodifiableSet(_restoredFiles);
	}

	/**
	 * Gets the changed files that belong to no element.
	 *
	 * @return the files
	 */
	@NotNull
	public Set<String> getIgnored() {
		return Collections.unmodifiableSet(_ignored);
	}

	/**
	 * Checks if there is nothing to import.
	 *
	 * @return true if the plan is empty
	 */
	public boolean isEmpty() {
		return _units.isEmpty() && _folders.isEmpty() && _wholeDirs.isEmpty();
	}
}
