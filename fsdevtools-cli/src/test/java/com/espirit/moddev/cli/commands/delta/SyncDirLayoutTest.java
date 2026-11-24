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

import com.espirit.moddev.cli.commands.delta.SyncDirLayout.PathStep;
import com.espirit.moddev.util.FileUtil;
import de.espirit.firstspirit.access.store.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SyncDirLayoutTest {

	@TempDir
	Path syncDir;

	@Test
	void normalizesNamesLikeFirstSpirit() {
		assertThat(SyncDirLayout.normalize("Developer Scripts (public)")).isEqualTo("Developer_Scripts__public_");
		assertThat(SyncDirLayout.normalize("PageTemplates (ID=87)")).isEqualTo("PageTemplates");
		assertThat(SyncDirLayout.normalize("homepage_1")).isEqualTo("homepage_1");
	}

	@Test
	void readsTheRootTag() throws IOException {
		final Path file = element("PageStore/folder/page", "PAGE", "page");
		assertThat(SyncDirLayout.readRootTag(file.resolve(SyncDirLayout.STORE_ELEMENT_XML))).isEqualTo("PAGE");
	}

	@Test
	void readsTheElementId() {
		final byte[] xml = "<?xml version=\"1.0\"?>\n<SECTION id=\"-3689381332604974921\" name=\"s\">\n\t<LANG id=\"7\"/>\n</SECTION>".getBytes(StandardCharsets.UTF_8);
		assertThat(SyncDirLayout.readElementId(xml)).isEqualTo("-3689381332604974921");
		assertThat(SyncDirLayout.readElementId("<SECTION name=\"s\"><LANG id=\"7\"/></SECTION>".getBytes(StandardCharsets.UTF_8))).isNull();
	}

	@Test
	void readsTheElementName() throws IOException {
		final Path xml = syncDir.resolve("teaser_new/StoreElement.xml");
		Files.createDirectories(xml.getParent());
		Files.writeString(xml, "<?xml version=\"1.0\"?>\n<SECTION id=\"1\" name=\"teaser\">\n\t<LANG displayname=\"Teaser\" language=\"EN\"/>\n</SECTION>");
		assertThat(SyncDirLayout.readElementName(xml)).isEqualTo("teaser");

		Files.writeString(xml, "<PAGEREF htmlname=\"company\" uniquedescription=\"company\"/>");
		assertThat(SyncDirLayout.readElementName(xml)).isNull();
	}

	@Test
	void resolvesAnElementPathByNameOrUid() throws IOException {
		element("TemplateStore", "TEMPLATESTORE", "root");
		element("TemplateStore/Scripts", "SCRIPTS", "Scripts (ID=99)");
		element("TemplateStore/Scripts/Developer_Scripts__public_", "SCRIPTFOLDER", "Developer Scripts (public)");
		final Path script = element("TemplateStore/Scripts/Developer_Scripts__public_/my_script", "SCRIPT", "My Script");

		final List<PathStep> steps = List.of(
				new PathStep(List.of("Scripts (ID=99)"), null),
				new PathStep(List.of("Developer Scripts (public)"), null),
				new PathStep(List.of("My Script", "my_script"), "SCRIPT"));

		assertThat(SyncDirLayout.resolve(syncDir, Store.Type.TEMPLATESTORE, steps)).contains(script);
	}

	@Test
	void doesNotResolveOnTagMismatch() throws IOException {
		element("PageStore/folder", "PAGENODE", "folder");
		element("PageStore/folder/page", "PAGE", "page");
		final List<PathStep> wrongTag = List.of(new PathStep(List.of("folder"), null), new PathStep(List.of("page"), "SECTION"));
		assertThat(SyncDirLayout.resolve(syncDir, Store.Type.PAGESTORE, wrongTag)).isEmpty();
	}

	@Test
	void tellsSiblingsWithTheSameFolderNameApartByTheirName() throws IOException {
		element("PageStore/folder", "PAGENODE", "folder");
		element("PageStore/folder/hero_banner", "PAGE", "hero_banner");
		final Path numbered = element("PageStore/folder/hero_banner_1", "PAGE", "hero-banner");
		final List<PathStep> steps = List.of(new PathStep(List.of("folder"), null), new PathStep(List.of("hero-banner"), "PAGE"));
		assertThat(SyncDirLayout.resolve(syncDir, Store.Type.PAGESTORE, steps)).contains(numbered);

		// the folder of the element is gone: the sibling is not taken for it
		FileUtil.deleteDirectory(numbered);
		assertThat(SyncDirLayout.resolve(syncDir, Store.Type.PAGESTORE, steps)).isEmpty();
	}

	@Test
	void readsTheUidOfMediaAndTableTemplates() throws IOException {
		final Path media = Files.createDirectories(syncDir.resolve("MediaStore/pic_2"));
		Files.writeString(media.resolve(SyncDirLayout.STORE_ELEMENT_XML), "<MEDIUM filename=\"pic-2\" id=\"1\" uniquedescription=\"pic_2\"/>");
		final Path table = Files.createDirectories(syncDir.resolve("TemplateStore/schema/schema.news"));
		Files.writeString(table.resolve(SyncDirLayout.STORE_ELEMENT_XML), "<TABLE description=\"schema.news\" filename=\"news\" id=\"2\"/>");

		assertThat(SyncDirLayout.resolve(syncDir, Store.Type.MEDIASTORE, List.of(new PathStep(List.of("pic_2"), "MEDIUM")))).contains(media);
		assertThat(SyncDirLayout.readIdentities(table.resolve(SyncDirLayout.STORE_ELEMENT_XML))).contains("schema.news");
	}

	@Test
	void prefersTheFolderWithTheIdenticalName() throws IOException {
		element("PageStore/folder", "PAGENODE", "folder");
		final Path dash = element("PageStore/folder/a-b", "PAGE", "a-b");
		element("PageStore/folder/a_b", "PAGE", "a_b");
		final List<PathStep> steps = List.of(new PathStep(List.of("folder"), null), new PathStep(List.of("a-b"), "PAGE"));
		assertThat(SyncDirLayout.resolve(syncDir, Store.Type.PAGESTORE, steps)).contains(dash);
	}

	@Test
	void restoresTheFlagsFirstSpiritChangesInAPartialExport() throws IOException {
		final Path existing = fsInfo("PageStore/folder", "AllChildren: 1\nExportVersion: 3.2\nHierarchicElement: true\n");
		final Path partialRoot = fsInfo("GlobalStore", "ExportVersion: 3.2\nExtraElement: true\n");
		final Map<Path, String> snapshot = SyncDirLayout.snapshotFsInfos(syncDir);

		// what FirstSpirit writes when it exports the folder as parent of an element, plus a new element without children
		Files.writeString(existing, "ExportVersion: 3.2\nExtraElement: true\nHierarchicElement: true\n");
		Files.writeString(partialRoot, "AllChildren: 1\nExportVersion: 3.2\n");
		final Path created = fsInfo("PageStore/folder/new_folder", "ExportVersion: 3.2\nHierarchicElement: true\n");

		assertThat(SyncDirLayout.restoreFsInfoFlags(syncDir, snapshot)).isEqualTo(3);
		assertThat(Files.readString(existing)).isEqualTo("AllChildren: 1\nExportVersion: 3.2\nHierarchicElement: true\n");
		assertThat(Files.readString(partialRoot)).isEqualTo("ExportVersion: 3.2\nExtraElement: true\n");
		assertThat(Files.readString(created)).isEqualTo("AllChildren: 1\nExportVersion: 3.2\nHierarchicElement: true\n");
	}

	private Path element(final String dir, final String tag, final String name) throws IOException {
		final Path path = Files.createDirectories(syncDir.resolve(dir));
		Files.writeString(path.resolve(SyncDirLayout.STORE_ELEMENT_XML),
				"<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<" + tag + " id=\"1\" name=\"" + name + "\"/>\n", StandardCharsets.UTF_8);
		return path;
	}

	private Path fsInfo(final String dir, final String content) throws IOException {
		final Path path = Files.createDirectories(syncDir.resolve(dir)).resolve(FsInfoFlags.FILE_NAME);
		Files.writeString(path, content);
		return path;
	}
}
