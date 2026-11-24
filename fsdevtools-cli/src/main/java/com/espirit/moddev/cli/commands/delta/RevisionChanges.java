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

import de.espirit.firstspirit.access.project.Project;
import de.espirit.firstspirit.access.store.BasicElementInfo;
import de.espirit.firstspirit.access.store.Store;
import de.espirit.firstspirit.storage.Revision;
import de.espirit.firstspirit.storage.RevisionMetaData;
import de.espirit.firstspirit.storage.RevisionOperation;
import de.espirit.or.EntityInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The store elements and entity types changed in a range of project revisions.
 */
public final class RevisionChanges {

	private static final Logger LOGGER = LoggerFactory.getLogger(RevisionChanges.class);

	/**
	 * Identifies a store element.
	 *
	 * @param storeType the store type
	 * @param nodeId    the node id
	 */
	public record ElementKey(@NotNull Store.Type storeType, long nodeId) {
	}

	/**
	 * Identifies an entity type.
	 *
	 * @param schemaId       the schema id
	 * @param schemaUid      the schema uid, may be null
	 * @param entityTypeName the entity type name
	 */
	public record EntityTypeKey(long schemaId, @Nullable String schemaUid, @NotNull String entityTypeName) {
	}

	private final Map<ElementKey, BasicElementInfo> _elements = new LinkedHashMap<>();
	private final Set<EntityTypeKey> _entityTypes = new LinkedHashSet<>();
	private final List<Long> _revisionsWithoutDetails = new ArrayList<>();
	private int _revisionCount;

	private RevisionChanges() {
	}

	/**
	 * Collects the changes of all revisions after {@code baseRevisionId} up to {@code target}.
	 *
	 * @param project             the project
	 * @param baseRevisionId      the last exported revision (excluded)
	 * @param target              the revision to export (included)
	 * @param includeReleaseState true to include release operations
	 * @return the changes
	 * @throws IOException if the revision metadata cannot be read
	 */
	@NotNull
	public static RevisionChanges collect(@NotNull final Project project, final long baseRevisionId, @NotNull final Revision target,
								   final boolean includeReleaseState) throws IOException {
		final RevisionChanges changes = new RevisionChanges();
		if (target.getId() <= baseRevisionId) {
			return changes;
		}
		final Revision base = project.getRevision(baseRevisionId);
		if (base == null) {
			throw new IllegalArgumentException("Revision " + baseRevisionId + " does not exist in project '" + project.getName() + "'.");
		}
		final List<Revision> revisions = new ArrayList<>(project.getRevisions(base, target, 0, null));
		revisions.sort(Comparator.comparingLong(Revision::getId));
		for (final Revision revision : revisions) {
			if (revision.getId() <= baseRevisionId || revision.getId() > target.getId()) {
				continue;
			}
			changes._revisionCount++;
			changes.add(revision, includeReleaseState);
		}
		return changes;
	}

	private void add(@NotNull final Revision revision, final boolean includeReleaseState) throws IOException {
		final RevisionMetaData metaData = revision.getMetaData();
		if (metaData == null) {
			_revisionsWithoutDetails.add(revision.getId());
			return;
		}
		final RevisionOperation<?> operation = metaData.getOperation();
		if (operation != null && !includeReleaseState && isRelease(operation.getType())) {
			LOGGER.debug("Skipping release revision {}", revision.getId());
			return;
		}
		// neither list is complete: script source changes are missing in the first, parents with changed children in the second
		boolean found = false;
		final Map<BasicElementInfo, ?> changed = metaData.getChangedStoreElements();
		if (changed != null) {
			for (final BasicElementInfo info : changed.keySet()) {
				found |= addElement(info);
			}
		}
		if (operation != null && operation.getElements() != null) {
			for (final Object element : operation.getElements()) {
				if (element instanceof BasicElementInfo info) {
					found |= addElement(info);
				} else if (element instanceof EntityInfo entity && entity.getEntityTypeName() != null) {
					_entityTypes.add(new EntityTypeKey(entity.getSchemaId(), entity.getSchemaUid(), entity.getEntityTypeName()));
					found = true;
				}
			}
		}
		if (!found) {
			LOGGER.debug("Revision {} ('{}') lists no changed elements", revision.getId(), revision.getComment());
			_revisionsWithoutDetails.add(revision.getId());
		}
	}

	private boolean addElement(@NotNull final BasicElementInfo info) {
		if (info.isEntity() || info.getStoreType() == null) {
			return false;
		}
		_elements.putIfAbsent(new ElementKey(info.getStoreType(), info.getNodeId()), info);
		return true;
	}

	private static boolean isRelease(@Nullable final RevisionOperation.OperationType type) {
		return type == RevisionOperation.OperationType.RELEASE || type == RevisionOperation.OperationType.SERVER_RELEASE;
	}

	/**
	 * Gets the changed store elements.
	 *
	 * @return the changed elements
	 */
	@NotNull
	public Map<ElementKey, BasicElementInfo> getElements() {
		return Collections.unmodifiableMap(_elements);
	}

	/**
	 * Gets the entity types with changed datasets.
	 *
	 * @return the entity types
	 */
	@NotNull
	public Set<EntityTypeKey> getEntityTypes() {
		return Collections.unmodifiableSet(_entityTypes);
	}

	/**
	 * Gets the revisions that name no changed element, for example media uploads.
	 *
	 * @return the revision ids
	 */
	@NotNull
	public List<Long> getRevisionsWithoutDetails() {
		return Collections.unmodifiableList(_revisionsWithoutDetails);
	}

	/**
	 * Gets the number of checked revisions.
	 *
	 * @return the number of revisions
	 */
	public int getRevisionCount() {
		return _revisionCount;
	}
}
