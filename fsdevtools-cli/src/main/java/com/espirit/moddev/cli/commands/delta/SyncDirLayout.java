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
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Utility methods for the folder layout of a sync directory. Every store element has its own folder with a {@code StoreElement.xml}.
 */
public final class SyncDirLayout {

	public static final String STORE_ELEMENT_XML = "StoreElement.xml";
	public static final String ENTITIES_DIR = "Entities";
	public static final String GLOBAL_DIR = "Global";

	private static final Map<Store.Type, String> STORE_DIRS = new EnumMap<>(Store.Type.class);
	private static final Pattern ID_SUFFIX = Pattern.compile(" \\(ID=-?\\d+\\)$");
	private static final Pattern ROOT_TAG = Pattern.compile("<([A-Za-z_][\\w.-]*)");
	private static final Pattern ROOT_ID = rootAttribute("id");
	private static final Pattern ROOT_NAME = rootAttribute("name");
	// uid or name: uniquedescription (media, page references), name, filename (templates), description (table templates)
	private static final List<Pattern> ROOT_IDENTITIES = List.of(rootAttribute("uniquedescription"), ROOT_NAME, rootAttribute("filename"),
			rootAttribute("description"));

	static {
		STORE_DIRS.put(Store.Type.PAGESTORE, "PageStore");
		STORE_DIRS.put(Store.Type.SITESTORE, "SiteStore");
		STORE_DIRS.put(Store.Type.MEDIASTORE, "MediaStore");
		STORE_DIRS.put(Store.Type.TEMPLATESTORE, "TemplateStore");
		STORE_DIRS.put(Store.Type.CONTENTSTORE, "ContentStore");
		STORE_DIRS.put(Store.Type.GLOBALSTORE, "GlobalStore");
	}

	private SyncDirLayout() {
	}

	/**
	 * Gets the folder name of a store.
	 *
	 * @param type the store type
	 * @return the folder name, or null if the store is not exported
	 */
	@Nullable
	public static String storeDirName(@NotNull final Store.Type type) {
		return STORE_DIRS.get(type);
	}

	/**
	 * Gets the existing store folders of a sync directory.
	 *
	 * @param syncDir the sync directory
	 * @return the store folders
	 */
	@NotNull
	public static List<Path> storeDirs(@NotNull final Path syncDir) {
		final List<Path> dirs = new ArrayList<>();
		for (final String name : STORE_DIRS.values()) {
			final Path dir = syncDir.resolve(name);
			if (Files.isDirectory(dir)) {
				dirs.add(dir);
			}
		}
		return dirs;
	}

	/**
	 * Checks if a folder belongs to a store element.
	 *
	 * @param dir the folder
	 * @return true if the folder contains a {@code StoreElement.xml}
	 */
	public static boolean isElementDir(@NotNull final Path dir) {
		return Files.isRegularFile(dir.resolve(STORE_ELEMENT_XML));
	}

	/**
	 * Normalizes a name like FirstSpirit does for folder names, for example {@code "Scripts (ID=99)"} to {@code Scripts}
	 * and {@code "Developer Scripts (public)"} to {@code Developer_Scripts__public_}.
	 *
	 * @param name the element or folder name
	 * @return the normalized name
	 */
	@NotNull
	public static String normalize(@NotNull final String name) {
		return ID_SUFFIX.matcher(name).replaceFirst("").replaceAll("[^A-Za-z0-9]", "_");
	}

	/**
	 * One element of a path.
	 *
	 * @param candidates the name and uid of the element
	 * @param nodeTag    the root tag of the {@code StoreElement.xml} ({@code PAGE}, {@code SECTION}, ...), or null if unknown
	 */
	public record PathStep(@NotNull List<String> candidates, @Nullable String nodeTag) {

		/**
		 * Gets the normalized names of the element, to compare paths.
		 *
		 * @return the key
		 */
		@NotNull
		public String key() {
			final List<String> normalized = new ArrayList<>();
			for (final String candidate : candidates) {
				normalized.add(normalize(candidate));
			}
			return String.join("|", normalized);
		}
	}

	/**
	 * Finds the folder of an element.
	 *
	 * @param syncDir the sync directory
	 * @param type    the store type
	 * @param steps   the path from the store root (excluded) to the element
	 * @return the folder, or empty if it is not found or not unique
	 * @throws IOException if a folder cannot be read
	 */
	@NotNull
	public static Optional<Path> resolve(@NotNull final Path syncDir, @NotNull final Store.Type type, @NotNull final List<PathStep> steps) throws IOException {
		final String storeDir = storeDirName(type);
		if (storeDir == null || steps.isEmpty()) {
			return Optional.empty();
		}
		Path current = syncDir.resolve(storeDir);
		for (final PathStep step : steps) {
			final Optional<Path> next = matchChild(current, step);
			if (next.isEmpty()) {
				return Optional.empty();
			}
			current = next.get();
		}
		final String expectedTag = steps.get(steps.size() - 1).nodeTag();
		if (expectedTag != null && !expectedTag.equalsIgnoreCase(readRootTag(current.resolve(STORE_ELEMENT_XML)))) {
			return Optional.empty();
		}
		return Optional.of(current);
	}

	@NotNull
	private static Optional<Path> matchChild(@NotNull final Path dir, @NotNull final PathStep step) throws IOException {
		if (!Files.isDirectory(dir)) {
			return Optional.empty();
		}
		final List<String> wanted = new ArrayList<>();
		for (final String candidate : step.candidates()) {
			wanted.add(normalize(candidate));
		}
		final List<Path> exact = new ArrayList<>();
		final List<Path> ignoringCase = new ArrayList<>();
		final List<Path> numbered = new ArrayList<>();
		try (Stream<Path> children = Files.list(dir)) {
			for (final Path child : (Iterable<Path>) children::iterator) {
				if (!isElementDir(child)) {
					continue;
				}
				final String name = normalize(child.getFileName().toString());
				if (wanted.contains(name)) {
					exact.add(child);
				} else if (wanted.stream().anyMatch(name::equalsIgnoreCase)) {
					ignoringCase.add(child);
				} else if (wanted.stream().anyMatch(candidate -> name.matches(Pattern.quote(candidate) + "_\\d+"))) {
					// FirstSpirit adds "_1", "_2", ... to a folder whose name a sibling already has
					numbered.add(child);
				}
			}
		}
		// siblings can share a folder name, for example "hero-banner" and "hero_banner": the name in StoreElement.xml decides
		for (final List<Path> matches : List.of(exact, ignoringCase, numbered)) {
			final List<Path> confirmed = new ArrayList<>();
			for (final Path match : matches) {
				final List<String> identities = readIdentities(match.resolve(STORE_ELEMENT_XML));
				if (identities.isEmpty() || identities.stream().anyMatch(identity -> step.candidates().stream().anyMatch(identity::equalsIgnoreCase))) {
					confirmed.add(match);
				}
			}
			if (!confirmed.isEmpty()) {
				return confirmed.size() == 1 ? Optional.of(confirmed.get(0)) : Optional.empty();
			}
		}
		return Optional.empty();
	}

	/**
	 * Reads the root tag of an XML file.
	 *
	 * @param xmlFile the file
	 * @return the tag name, or null if the file does not exist
	 * @throws IOException if the file cannot be read
	 */
	@Nullable
	public static String readRootTag(@NotNull final Path xmlFile) throws IOException {
		if (!Files.isRegularFile(xmlFile)) {
			return null;
		}
		final Matcher matcher = ROOT_TAG.matcher(readHead(xmlFile));
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * Reads the element id from the root tag of a {@code StoreElement.xml}.
	 *
	 * @param xmlFile the file
	 * @return the id, or null if the file does not exist or has no id
	 * @throws IOException if the file cannot be read
	 */
	@Nullable
	public static String readElementId(@NotNull final Path xmlFile) throws IOException {
		return Files.isRegularFile(xmlFile) ? readElementId(Files.readAllBytes(xmlFile)) : null;
	}

	/**
	 * Reads the element id from the root tag of a {@code StoreElement.xml}.
	 *
	 * @param content the file content
	 * @return the id, or null if the root tag has no id
	 */
	@Nullable
	public static String readElementId(@NotNull final byte[] content) {
		final Matcher matcher = ROOT_ID.matcher(stripProlog(new String(content, 0, Math.min(content.length, 1024), StandardCharsets.UTF_8)));
		return matcher.find() ? matcher.group(1) : null;
	}

	/**
	 * Reads the element name from the root tag of a {@code StoreElement.xml}. FirstSpirit takes the name from there on import,
	 * not from the folder name.
	 *
	 * @param xmlFile the file
	 * @return the name, or null if the file does not exist or the root tag has no name
	 * @throws IOException if the file cannot be read
	 */
	@Nullable
	public static String readElementName(@NotNull final Path xmlFile) throws IOException {
		if (!Files.isRegularFile(xmlFile)) {
			return null;
		}
		final Matcher matcher = ROOT_NAME.matcher(readHead(xmlFile));
		return matcher.find() ? unescape(matcher.group(1)) : null;
	}

	/**
	 * Reads the attributes of the root tag of a {@code StoreElement.xml} that can hold the uid or name of the element.
	 *
	 * @param xmlFile the file
	 * @return the values, empty if the file does not exist or the root tag has none of them
	 * @throws IOException if the file cannot be read
	 */
	@NotNull
	static List<String> readIdentities(@NotNull final Path xmlFile) throws IOException {
		final List<String> identities = new ArrayList<>();
		if (Files.isRegularFile(xmlFile)) {
			final String head = readHead(xmlFile);
			for (final Pattern pattern : ROOT_IDENTITIES) {
				final Matcher matcher = pattern.matcher(head);
				if (matcher.find()) {
					identities.add(unescape(matcher.group(1)));
				}
			}
		}
		return identities;
	}

	@NotNull
	private static Pattern rootAttribute(@NotNull final String name) {
		return Pattern.compile("^\\s*<[A-Za-z_][\\w.-]*[^>]*?\\s" + name + "=\"([^\"]*)\"");
	}

	@NotNull
	private static String unescape(@NotNull final String value) {
		return value.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
	}

	@NotNull
	private static String readHead(@NotNull final Path xmlFile) throws IOException {
		final byte[] head;
		try (InputStream in = Files.newInputStream(xmlFile)) {
			head = in.readNBytes(1024);
		}
		return stripProlog(new String(head, StandardCharsets.UTF_8));
	}

	@NotNull
	private static String stripProlog(@NotNull final String text) {
		return text.replaceAll("(?s)<\\?.*?\\?>", "").replaceAll("(?s)<!--.*?-->", "");
	}

	/**
	 * Reads all {@code FS_Info.txt} files of the store folders.
	 *
	 * @param syncDir the sync directory
	 * @return the file contents by path
	 * @throws IOException if a file cannot be read
	 */
	@NotNull
	public static Map<Path, String> snapshotFsInfos(@NotNull final Path syncDir) throws IOException {
		final Map<Path, String> snapshot = new HashMap<>();
		for (final Path file : listFsInfos(syncDir)) {
			snapshot.put(file, Files.readString(file, StandardCharsets.UTF_8));
		}
		return snapshot;
	}

	/**
	 * Restores the flags of all {@code FS_Info.txt} files a partial export changed, so that the sync directory looks like a full export.
	 *
	 * @param syncDir  the sync directory
	 * @param snapshot the contents before the export, see {@link #snapshotFsInfos(Path)}
	 * @return the number of changed files
	 * @throws IOException if a file cannot be read or written
	 */
	public static int restoreFsInfoFlags(@NotNull final Path syncDir, @NotNull final Map<Path, String> snapshot) throws IOException {
		int rewritten = 0;
		for (final Path file : listFsInfos(syncDir)) {
			final String current = Files.readString(file, StandardCharsets.UTF_8);
			final String previous = snapshot.get(file);
			if (current.equals(previous)) {
				continue;
			}
			// a new file belongs to a new element, which a full export writes with all children
			final boolean allChildren = previous == null || FsInfoFlags.hasAllChildren(previous);
			final boolean extraElement = previous != null && FsInfoFlags.hasExtraElement(previous);
			final String fixed = FsInfoFlags.withFlags(current, allChildren, extraElement);
			if (!fixed.equals(current)) {
				Files.writeString(file, fixed, StandardCharsets.UTF_8);
				rewritten++;
			}
		}
		return rewritten;
	}

	@NotNull
	private static List<Path> listFsInfos(@NotNull final Path syncDir) throws IOException {
		final List<Path> files = new ArrayList<>();
		for (final Path storeDir : storeDirs(syncDir)) {
			try (Stream<Path> paths = Files.walk(storeDir)) {
				paths.filter(path -> path.getFileName().toString().equals(FsInfoFlags.FILE_NAME))
						.filter(Files::isRegularFile)
						.forEach(files::add);
			}
		}
		return files;
	}
}
