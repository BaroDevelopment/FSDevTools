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

import de.espirit.firstspirit.access.store.IDProvider;
import de.espirit.firstspirit.access.store.Store;
import de.espirit.firstspirit.access.store.StoreElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Compares element folders with the elements on the server.
 */
public final class ServerElements {

	private ServerElements() {
	}

	/**
	 * The server element of an element folder.
	 *
	 * @param element     the element, or null if it does not exist on the server
	 * @param ambiguousAt the folder that matches more than one server element, or null
	 */
	public record Resolution(@Nullable StoreElement element, @Nullable String ambiguousAt) {
	}

	/**
	 * Finds the server element of an element folder. The folder name and the name in its {@code StoreElement.xml} are compared
	 * with the names and UIDs of the server elements. If several match, the exact name decides.
	 *
	 * @param stores  the stores to search, for example {@code type -> storeAgent.getStore(type, false)}
	 * @param syncDir the directory that contains the folder
	 * @param dir     the folder, relative to {@code syncDir}, for example {@code PageStore/folder/page}
	 * @return the element, or why it was not found
	 * @throws IOException if a {@code StoreElement.xml} cannot be read
	 */
	@NotNull
	public static Resolution resolve(@NotNull final Function<Store.Type, Store> stores, @NotNull final Path syncDir, @NotNull final String dir) throws IOException {
		final String[] segments = dir.split("/");
		Store.Type type = null;
		for (final Store.Type candidate : Store.Type.values()) {
			if (segments[0].equals(SyncDirLayout.storeDirName(candidate))) {
				type = candidate;
			}
		}
		if (type == null) {
			return new Resolution(null, null);
		}
		StoreElement current = stores.apply(type);
		Path local = syncDir.resolve(segments[0]);
		for (int i = 1; i < segments.length; i++) {
			local = local.resolve(segments[i]);
			final List<StoreElement> matches = matchChildren(current, local);
			if (matches.isEmpty()) {
				return new Resolution(null, null);
			}
			if (matches.size() > 1) {
				return new Resolution(null, String.join("/", List.of(segments).subList(0, i + 1)));
			}
			current = matches.get(0);
		}
		return new Resolution(current, null);
	}

	/**
	 * Lists the server elements that have no local folder. An import would delete them.
	 *
	 * @param element    the server element
	 * @param localDir   the local folder of the element
	 * @param renamedDirs the old folders of elements renamed locally, which FirstSpirit renames on import instead of deleting them
	 * @return the paths of the missing elements, below the element
	 * @throws IOException if a folder cannot be read
	 */
	@NotNull
	public static List<String> missingLocally(@NotNull final StoreElement element, @NotNull final Path localDir, @NotNull final Set<Path> renamedDirs)
			throws IOException {
		final List<String> missing = new ArrayList<>();
		collectMissing(element, localDir, renamedDirs, "", missing);
		return missing;
	}

	private static void collectMissing(@NotNull final StoreElement element, @NotNull final Path localDir, @NotNull final Set<Path> renamedDirs,
									   @NotNull final String prefix, @NotNull final List<String> missing) throws IOException {
		// sorted, so that of the folders FirstSpirit writes for elements with the same name ("teaser", "teaser_1", ...) the first comes first
		final Map<Path, List<String>> localChildren = new TreeMap<>();
		if (Files.isDirectory(localDir)) {
			final List<Path> dirs;
			try (Stream<Path> children = Files.list(localDir)) {
				dirs = children.filter(SyncDirLayout::isElementDir).toList();
			}
			for (final Path dir : dirs) {
				localChildren.put(dir, localNames(dir));
			}
		}
		// every local folder belongs to one server element at most
		final Map<StoreElement, Path> matched = new LinkedHashMap<>();
		final Set<Path> taken = new HashSet<>();
		for (final StoreElement child : element.getChildren()) {
			if (!(child instanceof IDProvider)) {
				continue;
			}
			final Path match = localChildren.entrySet().stream()
					.filter(entry -> !taken.contains(entry.getKey()) && matchesAny(child, entry.getValue()))
					.map(Map.Entry::getKey)
					.findFirst()
					.orElse(null);
			if (match != null) {
				taken.add(match);
				matched.put(child, match);
			} else if (!wasRenamed(child, localDir, renamedDirs)) {
				missing.add(prefix + displayName(child));
			}
		}
		for (final Map.Entry<StoreElement, Path> entry : matched.entrySet()) {
			collectMissing(entry.getKey(), entry.getValue(), renamedDirs, prefix + displayName(entry.getKey()) + "/", missing);
		}
	}

	private static boolean wasRenamed(@NotNull final StoreElement element, @NotNull final Path localDir, @NotNull final Set<Path> renamedDirs) {
		for (final Path old : renamedDirs) {
			if (localDir.equals(old.getParent()) && matches(element, old.getFileName().toString())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Finds the children of a server element that match a local folder, narrowed down by exact name if several match after
	 * normalizing.
	 */
	@NotNull
	private static List<StoreElement> matchChildren(@NotNull final StoreElement parent, @NotNull final Path localDir) throws IOException {
		final List<String> names = localNames(localDir);
		final List<StoreElement> matches = new ArrayList<>();
		for (final StoreElement child : parent.getChildren()) {
			if (child instanceof IDProvider && matchesAny(child, names)) {
				matches.add(child);
			}
		}
		if (matches.size() > 1) {
			final List<StoreElement> exact = matches.stream().filter(child -> names.contains(String.valueOf(child.getName())) || names.contains(String.valueOf(uidOf(child)))).toList();
			if (exact.size() == 1) {
				return exact;
			}
		}
		return matches;
	}

	/**
	 * Gets the names an element folder can be matched by: the folder name, and the name in its {@code StoreElement.xml}, which
	 * differs after the folder was renamed locally.
	 */
	@NotNull
	private static List<String> localNames(@NotNull final Path localDir) throws IOException {
		final String dirName = localDir.getFileName().toString();
		final String elementName = SyncDirLayout.readElementName(localDir.resolve(SyncDirLayout.STORE_ELEMENT_XML));
		return elementName == null || elementName.equals(dirName) ? List.of(dirName) : List.of(dirName, elementName);
	}

	private static boolean matchesAny(@NotNull final StoreElement element, @NotNull final List<String> names) {
		return names.stream().anyMatch(name -> matches(element, name));
	}

	private static boolean matches(@NotNull final StoreElement element, @NotNull final String dirName) {
		final String wanted = SyncDirLayout.normalize(dirName);
		final String name = element.getName();
		if (name != null && SyncDirLayout.normalize(name).equalsIgnoreCase(wanted)) {
			return true;
		}
		final String uid = uidOf(element);
		return uid != null && SyncDirLayout.normalize(uid).equalsIgnoreCase(wanted);
	}

	/**
	 * Gets the UID of an element, or its name if it has no UID, for example a section.
	 *
	 * @param element the element
	 * @return the name
	 */
	@NotNull
	static String displayName(@NotNull final StoreElement element) {
		final String uid = uidOf(element);
		return uid != null ? uid : String.valueOf(element.getName());
	}

	@Nullable
	static String uidOf(@NotNull final StoreElement element) {
		if (!(element instanceof IDProvider provider)) {
			return null;
		}
		try {
			return provider.getUid();
		} catch (final RuntimeException e) {
			return null;
		}
	}
}
