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

import com.espirit.moddev.cli.commands.delta.RevisionChanges.ElementKey;
import com.espirit.moddev.cli.commands.delta.RevisionChanges.EntityTypeKey;
import com.espirit.moddev.cli.commands.delta.SyncDirLayout.PathStep;
import de.espirit.firstspirit.access.UserService;
import de.espirit.firstspirit.access.project.Project;
import de.espirit.firstspirit.access.store.BasicElementInfo;
import de.espirit.firstspirit.access.store.IDProvider;
import de.espirit.firstspirit.access.store.Store;
import de.espirit.firstspirit.access.store.StoreElement;
import de.espirit.firstspirit.access.store.contentstore.Content2;
import de.espirit.firstspirit.access.store.contentstore.ContentStoreRoot;
import de.espirit.firstspirit.access.store.templatestore.Schema;
import de.espirit.firstspirit.agency.StoreAgent;
import de.espirit.firstspirit.storage.Revision;
import de.espirit.or.schema.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which elements a delta export exports and which folders it deletes.
 * <ul>
 *     <li>Units (a page, template, media, ...) are exported with their children.</li>
 *     <li>Folders are exported without their children.</li>
 *     <li>SiteStore elements are exported without their children, together with their siblings, see {@link #addSiteStoreSiblings(Store)}.</li>
 *     <li>Folders of deleted, moved or renamed elements are deleted.</li>
 *     <li>Content sources with changed datasets are exported completely.</li>
 * </ul>
 */
public final class DeltaExportPlan {

	private static final Logger LOGGER = LoggerFactory.getLogger(DeltaExportPlan.class);

	private final Map<ElementKey, IDProvider> _units = new LinkedHashMap<>();
	private final Map<ElementKey, IDProvider> _folders = new LinkedHashMap<>();
	private final Set<Path> _staleDirs = new LinkedHashSet<>();
	private final Set<Path> _replacedDirs = new LinkedHashSet<>();
	private final Set<String> _entitySources = new LinkedHashSet<>();
	private final Set<Long> _siteStoreParents = new LinkedHashSet<>();
	private final List<String> _notes = new ArrayList<>();

	private DeltaExportPlan() {
	}

	/**
	 * Creates the plan for the given changes.
	 *
	 * @param project        the project
	 * @param storeAgent     the store agent
	 * @param syncDir        the sync directory
	 * @param baseRevisionId the last exported revision
	 * @param changes        the changes since the last export
	 * @param releaseState   true to export the release state
	 * @return the plan
	 * @throws IOException if the sync directory cannot be read
	 */
	@NotNull
	public static DeltaExportPlan create(@NotNull final Project project, @NotNull final StoreAgent storeAgent, @NotNull final Path syncDir,
								  final long baseRevisionId, @NotNull final RevisionChanges changes, final boolean releaseState) throws IOException {
		final DeltaExportPlan plan = new DeltaExportPlan();
		final Revision baseRevision = project.getRevision(baseRevisionId);
		final UserService baseService = baseRevision == null ? null : project.getUserService(baseRevision);
		final Map<Store.Type, Store> currentStores = new EnumMap<>(Store.Type.class);
		final Map<Store.Type, Store> baseStores = new EnumMap<>(Store.Type.class);

		for (final Map.Entry<ElementKey, BasicElementInfo> entry : changes.getElements().entrySet()) {
			final ElementKey key = entry.getKey();
			final Store.Type type = key.storeType();
			if (SyncDirLayout.storeDirName(type) == null) {
				continue;
			}
			final IDProvider current = lookup(currentStores.computeIfAbsent(type, t -> storeAgent.getStore(t, releaseState)), key.nodeId());
			final IDProvider old = baseService == null ? null
					: lookupAtBase(baseStores.computeIfAbsent(type, t -> baseService.getStore(t, releaseState)), key.nodeId());
			plan.add(syncDir, type, entry.getValue(), current, old);
		}
		// elements below a unit are exported with it
		plan._units.entrySet().removeIf(entry -> hasAncestorIn(entry.getKey().storeType(), entry.getValue(), plan._units.keySet()));
		plan._folders.entrySet().removeIf(entry -> plan._units.containsKey(entry.getKey())
				|| hasAncestorIn(entry.getKey().storeType(), entry.getValue(), plan._units.keySet()));
		// folders below a deleted folder go with it
		plan._staleDirs.removeIf(dir -> isBelowAny(dir, plan._staleDirs));
		plan._replacedDirs.removeIf(dir -> isBelowAny(dir, plan._replacedDirs) || isBelowAny(dir, plan._staleDirs) || plan._staleDirs.contains(dir));
		plan.addSiteStoreSiblings(currentStores.get(Store.Type.SITESTORE));
		plan.addEntitySources(storeAgent, syncDir, changes.getEntityTypes(), releaseState);
		return plan;
	}

	private static boolean hasAncestorIn(@NotNull final Store.Type type, @NotNull final IDProvider element, @NotNull final Set<ElementKey> keys) {
		StoreElement parent = element.getParent();
		while (parent instanceof IDProvider provider && !(parent instanceof Store)) {
			if (keys.contains(new ElementKey(type, provider.getId()))) {
				return true;
			}
			parent = parent.getParent();
		}
		return false;
	}

	static boolean isBelowAny(@NotNull final Path dir, @NotNull final Set<Path> dirs) {
		for (Path parent = dir.getParent(); parent != null; parent = parent.getParent()) {
			if (dirs.contains(parent)) {
				return true;
			}
		}
		return false;
	}

	private void add(@NotNull final Path syncDir, @NotNull final Store.Type type, @NotNull final BasicElementInfo info,
					 @Nullable final IDProvider current, @Nullable final IDProvider old) throws IOException {
		boolean moved = false;
		if (old != null && !(old instanceof Store)) {
			final List<PathStep> oldPath = path(old, info.getNodeTag());
			final List<PathStep> newPath = current == null ? null : path(current, info.getNodeTag());
			if (newPath == null || !keys(oldPath).equals(keys(newPath))) {
				final Optional<Path> oldDir = SyncDirLayout.resolve(syncDir, type, oldPath);
				if (oldDir.isPresent()) {
					_staleDirs.add(oldDir.get());
				} else if (newPath == null || SyncDirLayout.resolve(syncDir, type, newPath).isEmpty()) {
					// a moved element whose folder is already at the new place, for example after importDelta, needs no note
					_notes.add("No folder found for " + (current == null ? "deleted" : "moved") + " element " + describe(type, info) + " - nothing to delete");
				}
				moved = current != null;
			}
		}
		if (type == Store.Type.SITESTORE) {
			// the order changed among the old and the new siblings
			addParentId(old);
			addParentId(current);
		}
		if (current == null) {
			return;
		}
		if (moved && isContainer(current)) {
			// with its children, which moved with it
			_units.put(new ElementKey(type, current.getId()), current);
		} else if (isContainer(current)) {
			_folders.put(new ElementKey(type, current.getId()), current);
		} else {
			final IDProvider unit = unitOf(current);
			// delete the unit folder before the export, so that removed files disappear
			if (_units.putIfAbsent(new ElementKey(type, unit.getId()), unit) == null) {
				SyncDirLayout.resolve(syncDir, type, path(unit, null)).ifPresent(_replacedDirs::add);
			}
		}
	}

	private void addParentId(@Nullable final IDProvider element) {
		final IDProvider parent = element == null ? null : element.getParent();
		if (parent != null) {
			_siteStoreParents.add(parent.getId());
		}
	}

	/**
	 * FirstSpirit writes the order of SiteStore elements ({@code previousSibling} and {@code nextSibling} in {@code FS_References.txt}) only
	 * between elements of the same export. So the changed SiteStore elements are exported in one export without children, together with all
	 * their old and new siblings; a moved folder with all elements below it.
	 */
	private void addSiteStoreSiblings(@Nullable final Store siteStore) {
		if (siteStore == null || _siteStoreParents.isEmpty()) {
			return;
		}
		final List<IDProvider> elements = new ArrayList<>();
		final Iterator<Map.Entry<ElementKey, IDProvider>> units = _units.entrySet().iterator();
		while (units.hasNext()) {
			final Map.Entry<ElementKey, IDProvider> entry = units.next();
			if (entry.getKey().storeType() == Store.Type.SITESTORE) {
				units.remove();
				final IDProvider unit = entry.getValue();
				elements.add(unit);
				unit.getChildren(IDProvider.class, true).forEach(elements::add);
			}
		}
		for (final long parentId : _siteStoreParents) {
			final IDProvider parent = lookup(siteStore, parentId);
			if (parent != null) {
				parent.getChildren(IDProvider.class, false).forEach(elements::add);
			}
		}
		for (final IDProvider element : elements) {
			_folders.put(new ElementKey(Store.Type.SITESTORE, element.getId()), element);
		}
	}

	private void addEntitySources(@NotNull final StoreAgent storeAgent, @NotNull final Path syncDir,
								  @NotNull final Collection<EntityTypeKey> entityTypes, final boolean releaseState) {
		if (entityTypes.isEmpty()) {
			return;
		}
		final Store store = storeAgent.getStore(Store.Type.CONTENTSTORE, releaseState);
		if (!(store instanceof ContentStoreRoot contentStore)) {
			return;
		}
		final Set<EntityTypeKey> matched = new LinkedHashSet<>();
		for (final Content2 content2 : contentStore.getChildren(Content2.class, true)) {
			final Schema schema = content2.getSchema();
			final EntityType entityType = content2.getEntityType();
			if (schema == null || entityType == null) {
				continue;
			}
			for (final EntityTypeKey key : entityTypes) {
				final boolean sameSchema = schema.getId() == key.schemaId() || Objects.equals(schema.getUid(), key.schemaUid());
				if (!sameSchema || !entityType.getName().equals(key.entityTypeName())) {
					continue;
				}
				matched.add(key);
				final Optional<Path> entitiesDir = entitiesDir(syncDir, schema.getUid(), entityType.getName());
				if (entitiesDir.isPresent()) {
					_entitySources.add(content2.getUid());
					// exported again from scratch, so that datasets deleted on the server disappear
					_replacedDirs.add(entitiesDir.get());
				} else {
					_notes.add("Datasets of '" + content2.getUid() + "' changed, but " + SyncDirLayout.ENTITIES_DIR + "/" + schema.getUid() + "/"
							+ entityType.getName() + " is not part of the sync directory - skipped (pass entities:" + content2.getUid() + " to export them)");
				}
			}
		}
		for (final EntityTypeKey key : entityTypes) {
			if (!matched.contains(key)) {
				_notes.add("Datasets of entity type '" + key.entityTypeName() + "' (schema id " + key.schemaId() + ") changed, but no content source uses it - skipped");
			}
		}
	}

	@NotNull
	private static Optional<Path> entitiesDir(@NotNull final Path syncDir, @Nullable final String schemaUid, @NotNull final String entityType) {
		if (schemaUid == null) {
			return Optional.empty();
		}
		final Path entities = syncDir.resolve(SyncDirLayout.ENTITIES_DIR);
		final Path exact = entities.resolve(schemaUid).resolve(entityType);
		if (Files.isDirectory(exact)) {
			return Optional.of(exact);
		}
		final Path normalized = entities.resolve(SyncDirLayout.normalize(schemaUid)).resolve(SyncDirLayout.normalize(entityType));
		return Files.isDirectory(normalized) ? Optional.of(normalized) : Optional.empty();
	}

	/**
	 * Checks if an element is a folder, which is exported without its children. Content sources are not.
	 *
	 * @param element the element
	 * @return true for folders
	 */
	public static boolean isContainer(@NotNull final StoreElement element) {
		return element.isFolder() && !(element instanceof Content2);
	}

	/**
	 * Gets the unit of an element, for example the page of a section.
	 *
	 * @param element the element
	 * @return the topmost parent (or the element itself) that is not a folder
	 */
	@NotNull
	public static IDProvider unitOf(@NotNull final IDProvider element) {
		IDProvider unit = element;
		StoreElement parent = unit.getParent();
		while (parent instanceof IDProvider parentProvider && !isContainer(parent)) {
			unit = parentProvider;
			parent = unit.getParent();
		}
		return unit;
	}

	/**
	 * Gets the path of an element.
	 *
	 * @param element the element
	 * @param nodeTag the node tag of the element, or null
	 * @return the path from the store root (excluded) to the element
	 */
	@NotNull
	public static List<PathStep> path(@NotNull final StoreElement element, @Nullable final String nodeTag) {
		final List<PathStep> steps = new ArrayList<>();
		StoreElement current = element;
		while (current != null && !(current instanceof Store)) {
			steps.add(0, step(current, current == element ? nodeTag : null));
			current = current.getParent();
		}
		return steps;
	}

	@NotNull
	private static PathStep step(@NotNull final StoreElement element, @Nullable final String nodeTag) {
		final List<String> candidates = new ArrayList<>();
		final String name = element.getName();
		if (name != null && !name.isEmpty()) {
			candidates.add(name);
		}
		final String uid = ServerElements.uidOf(element);
		if (uid != null && !uid.isEmpty() && !uid.equals(name)) {
			candidates.add(uid);
		}
		return new PathStep(candidates, nodeTag);
	}

	@NotNull
	private static List<String> keys(@NotNull final List<PathStep> steps) {
		final List<String> keys = new ArrayList<>();
		for (final PathStep step : steps) {
			keys.add(step.key());
		}
		return keys;
	}

	@Nullable
	private static IDProvider lookup(@Nullable final Store store, final long nodeId) {
		return store == null ? null : store.getStoreElement(nodeId);
	}

	/**
	 * Looks up an element at the base revision. If that fails, the element is treated as new, so that no folder is deleted.
	 */
	@Nullable
	private static IDProvider lookupAtBase(@Nullable final Store store, final long nodeId) {
		try {
			return lookup(store, nodeId);
		} catch (final RuntimeException e) {
			LOGGER.debug("Could not load element {} from {} at the base revision", nodeId, store.getType(), e);
			return null;
		}
	}

	/**
	 * Describes an element for logging.
	 *
	 * @param element the element
	 * @return the description
	 */
	@NotNull
	public static String describe(@NotNull final IDProvider element) {
		return element.getElementType() + " '" + ServerElements.displayName(element) + "' (id " + element.getId() + ")";
	}

	@NotNull
	private static String describe(@NotNull final Store.Type type, @NotNull final BasicElementInfo info) {
		return type + " " + info.getNodeTag() + " '" + (info.getUid() != null ? info.getUid() : info.getName()) + "' (id " + info.getNodeId() + ")";
	}

	/**
	 * Gets the elements to export with their children.
	 *
	 * @return the units
	 */
	@NotNull
	public Collection<IDProvider> getUnits() {
		return _units.values();
	}

	/**
	 * Gets the folders to export without their children.
	 *
	 * @return the folders
	 */
	@NotNull
	public Collection<IDProvider> getFolders() {
		return _folders.values();
	}

	/**
	 * Gets the folders of deleted, moved or renamed elements.
	 *
	 * @return the folders to delete
	 */
	@NotNull
	public Set<Path> getStaleDirs() {
		return _staleDirs;
	}

	/**
	 * Gets the folders of units, which are deleted before the export.
	 *
	 * @return the folders to replace
	 */
	@NotNull
	public Set<Path> getReplacedDirs() {
		return _replacedDirs;
	}

	/**
	 * Gets the content sources whose datasets are exported.
	 *
	 * @return the content source uids
	 */
	@NotNull
	public Set<String> getEntitySources() {
		return _entitySources;
	}

	/**
	 * Gets notes for the user.
	 *
	 * @return the notes
	 */
	@NotNull
	public List<String> getNotes() {
		return _notes;
	}

	/**
	 * Checks if there is nothing to export or delete.
	 *
	 * @return true if the plan is empty
	 */
	public boolean isEmpty() {
		return _units.isEmpty() && _folders.isEmpty() && _staleDirs.isEmpty() && _entitySources.isEmpty();
	}
}
