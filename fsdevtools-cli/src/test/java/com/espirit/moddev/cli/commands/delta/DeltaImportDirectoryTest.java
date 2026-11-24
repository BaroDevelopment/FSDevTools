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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DeltaImportDirectoryTest {

	private static final String FOLDER_INFO = "AllChildren: 1\nExportVersion: 3.2\nHierarchicElement: true\n";
	private static final String ELEMENT_INFO = "AllChildren: 1\nExportVersion: 3.2\n";

	private static final DeltaImportDirectory.DeletedFileSource NO_ID = path -> ("restored " + path).getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path syncDir;

	@TempDir
	Path target;

	@BeforeEach
	void createSyncDir() throws IOException {
		element("PageStore", "PAGESTORE", FOLDER_INFO);
		element("PageStore/folder", "PAGENODE", FOLDER_INFO);
		element("PageStore/folder/page", "PAGE", ELEMENT_INFO);
		file("PageStore/folder/page/data.xml", "page data");
		element("PageStore/folder/page/content", "BODY", ELEMENT_INFO);
		file("PageStore/folder/page/content/FS_Children.txt", "page~#~content~#~s1\npage~#~content~#~s2\n");
		element("PageStore/folder/page/content/s1", "SECTION", ELEMENT_INFO);
		file("PageStore/folder/page/content/s1/data.xml", "s1 data");
		element("PageStore/folder/other", "PAGE", ELEMENT_INFO);
		file("PageStore/folder/other/data.xml", "other data");
		element("TemplateStore", "TEMPLATESTORE", FOLDER_INFO);
		element("TemplateStore/Schemes", "SCHEMES", FOLDER_INFO);
		element("TemplateStore/Schemes/myschema", "SCHEMA", ELEMENT_INFO);
		element("TemplateStore/Schemes/myschema/myschema.news", "TABLETEMPLATE", ELEMENT_INFO);
		file("Entities/myschema/news/Entities.xml", "<DATA/>");
		file("Global/Project/Project.xml", "<PROJECT/>");
		file(".FirstSpirit/Files_localhost_15.txt", "files");
		file(".FirstSpirit/fs-cli-delta-export_localhost_15.properties", "revision=1");
	}

	@Test
	void mapsChangedFilesToUnitsFoldersAndWholeDirectories() throws IOException {
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes(), NO_ID);

		assertThat(plan.getUnits()).containsExactly("PageStore/folder/page", "TemplateStore/Schemes/myschema");
		assertThat(plan.getFolders()).containsExactly("PageStore/folder");
		assertThat(plan.getWholeDirs()).containsExactly("Entities/myschema/news", "Global/Project");
		assertThat(plan.getDeletedElementDirs()).containsExactly("PageStore/folder/gone", "PageStore/folder/page/content/s2");
		assertThat(plan.getRestoredFiles()).containsExactly("PageStore/folder/page/content/s2/StoreElement.xml");
		assertThat(plan.getIgnored()).containsExactly("loose.txt");
	}

	@Test
	void buildsAPartialExport() throws IOException {
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes(), NO_ID);
		plan.build(syncDir, target, NO_ID);

		// no bookkeeping: FirstSpirit would read the checksums of the whole sync directory
		assertThat(target.resolve(".FirstSpirit")).doesNotExist();
		// the unit with everything below it, including the locally deleted section
		assertThat(read("PageStore/folder/page/data.xml")).isEqualTo("page data");
		assertThat(read("PageStore/folder/page/content/s1/data.xml")).isEqualTo("s1 data");
		assertThat(read("PageStore/folder/page/content/s2/StoreElement.xml")).isEqualTo("restored PageStore/folder/page/content/s2/StoreElement.xml");
		assertThat(read("PageStore/folder/page/FS_Info.txt")).isEqualTo(ELEMENT_INFO);
		// the folder with everything below it, as FirstSpirit cannot import a folder alone
		assertThat(read("PageStore/folder/FS_Info.txt")).isEqualTo(FOLDER_INFO);
		assertThat(read("PageStore/folder/other/data.xml")).isEqualTo("other data");
		// parents are stubs
		assertThat(read("PageStore/FS_Info.txt")).isEqualTo("ExportVersion: 3.2\nExtraElement: true\nHierarchicElement: true\n");
		assertThat(read("TemplateStore/Schemes/FS_Info.txt")).isEqualTo("ExportVersion: 3.2\nExtraElement: true\nHierarchicElement: true\n");
		// datasets with their schema, project settings
		assertThat(read("Entities/myschema/news/Entities.xml")).isEqualTo("<DATA/>");
		assertThat(target.resolve("TemplateStore/Schemes/myschema/myschema.news/StoreElement.xml")).exists();
		assertThat(read("Global/Project/Project.xml")).isEqualTo("<PROJECT/>");
	}

	@Test
	void skippedUnitsAreRemovedAndParentsWithoutContentPruned() throws IOException {
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes(), NO_ID);
		plan.build(syncDir, target, NO_ID);

		plan.demote(target, "PageStore/folder/page");
		plan.demote(target, "PageStore/folder");
		plan.pruneParents(target);

		assertThat(plan.getUnits()).containsExactly("TemplateStore/Schemes/myschema");
		assertThat(plan.getFolders()).isEmpty();
		assertThat(plan.getRestoredFiles()).isEmpty();
		assertThat(target.resolve("PageStore")).doesNotExist();
		assertThat(read("TemplateStore/Schemes/FS_Info.txt")).isEqualTo("ExportVersion: 3.2\nExtraElement: true\nHierarchicElement: true\n");
		assertThat(target.resolve("TemplateStore/Schemes/myschema/myschema.news/StoreElement.xml")).exists();
	}

	@Test
	void aSkippedFolderKeepsThePlannedElementsBelowIt() throws IOException {
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes(), NO_ID);
		plan.build(syncDir, target, NO_ID);

		plan.demote(target, "PageStore/folder");
		plan.pruneParents(target);

		assertThat(plan.getFolders()).isEmpty();
		assertThat(read("PageStore/folder/FS_Info.txt")).isEqualTo("ExportVersion: 3.2\nExtraElement: true\nHierarchicElement: true\n");
		assertThat(read("PageStore/folder/page/content/s1/data.xml")).isEqualTo("s1 data");
		assertThat(read("PageStore/folder/page/content/s2/StoreElement.xml")).isEqualTo("restored PageStore/folder/page/content/s2/StoreElement.xml");
		assertThat(target.resolve("PageStore/folder/other")).doesNotExist();
	}

	@Test
	void recognizesASectionRenamedLocally() throws IOException {
		FileUtil.deleteDirectory(syncDir.resolve("PageStore/folder/page/content/s1"));
		file("PageStore/folder/page/content/s1_new/StoreElement.xml", "<SECTION id=\"42\" name=\"s1_new\"/>");
		file("PageStore/folder/page/content/s1_new/FS_Info.txt", ELEMENT_INFO);
		final GitChanges changes = new GitChanges("HEAD",
				Set.of("PageStore/folder/page/content/s1_new/StoreElement.xml", "PageStore/folder/page/content/s1_new/FS_Info.txt"),
				Set.of("PageStore/folder/page/content/s1/StoreElement.xml", "PageStore/folder/page/content/s1/FS_Info.txt", "PageStore/folder/page/content/s1/data.xml"));

		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes, path -> "<SECTION id=\"42\" name=\"s1\"/>".getBytes(StandardCharsets.UTF_8));

		assertThat(plan.getUnits()).containsExactly("PageStore/folder/page");
		assertThat(plan.getRenamedDirs()).containsExactly("PageStore/folder/page/content/s1");
		assertThat(plan.getRestoredFiles()).isEmpty();
		assertThat(plan.getDeletedElementDirs()).isEmpty();
	}

	@Test
	void keepsALocallyDeletedSectionWithoutUpdatingIt() throws IOException {
		final GitChanges changes = new GitChanges("HEAD", Set.of("PageStore/folder/page/content/s1/data.xml"),
				Set.of("PageStore/folder/page/content/s2/StoreElement.xml", "PageStore/folder/page/content/s2/FS_Info.txt"));
		final DeltaImportDirectory.DeletedFileSource oldFiles = path -> (path.endsWith("FS_Info.txt") ? ELEMENT_INFO : "<SECTION id=\"7\" name=\"s2\"/>")
				.getBytes(StandardCharsets.UTF_8);
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes, oldFiles);
		plan.build(syncDir, target, oldFiles);
		plan.pruneParents(target);

		// the old content would overwrite a newer one on the server
		assertThat(read("PageStore/folder/page/content/s2/FS_Info.txt")).isEqualTo("ExportVersion: 3.2\nExtraElement: true\n");
	}

	@Test
	void ignoresFirstSpiritBookkeeping() throws IOException {
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, new GitChanges("HEAD", Set.of(".FirstSpirit/Files_localhost_15.txt"), Set.of()), NO_ID);
		assertThat(plan.isEmpty()).isTrue();
		assertThat(plan.getIgnored()).isEmpty();
	}

	@Test
	void doesNotRestoreAMovedSection() throws IOException {
		file("PageStore/folder/other/content/StoreElement.xml", "<BODY id=\"20\" name=\"content\"/>");
		file("PageStore/folder/other/content/FS_Info.txt", ELEMENT_INFO);
		file("PageStore/folder/other/content/s2/StoreElement.xml", "<SECTION id=\"42\" name=\"s2\"/>");
		file("PageStore/folder/other/content/s2/FS_Info.txt", ELEMENT_INFO);
		final GitChanges changes = new GitChanges("HEAD",
				Set.of("PageStore/folder/other/content/s2/StoreElement.xml", "PageStore/folder/other/content/s2/FS_Info.txt"),
				Set.of("PageStore/folder/page/content/s2/StoreElement.xml", "PageStore/folder/page/content/s2/FS_Info.txt"));

		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes, path -> "<SECTION id=\"42\" name=\"s2\"/>".getBytes(StandardCharsets.UTF_8));

		assertThat(plan.getUnits()).containsExactly("PageStore/folder/other");
		assertThat(plan.getRenamedDirs()).isEmpty();
		assertThat(plan.getRestoredFiles()).isEmpty();
		assertThat(plan.getDeletedElementDirs()).isEmpty();
	}

	@Test
	void demotingASchemaAlsoSkipsItsDatasets() throws IOException {
		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, changes(), NO_ID);
		plan.build(syncDir, target, NO_ID);

		assertThat(plan.demote(target, "TemplateStore/Schemes/myschema")).containsExactly("Entities/myschema/news");

		assertThat(plan.getWholeDirs()).containsExactly("Global/Project");
		assertThat(target.resolve("Entities/myschema/news/Entities.xml")).doesNotExist();
		assertThat(target.resolve("TemplateStore/Schemes/myschema/myschema.news")).doesNotExist();
	}

	@Test
	void mapsDatasetsToTheirSchemaOnly() throws IOException {
		element("TemplateStore/Schemes/news", "SCHEMAFOLDER", FOLDER_INFO);
		element("TemplateStore/Schemes/news/news", "SCHEMA", ELEMENT_INFO);
		file("Entities/news/article/Entities.xml", "<DATA/>");

		final DeltaImportDirectory plan = DeltaImportDirectory.plan(syncDir, new GitChanges("HEAD", Set.of("Entities/news/article/Entities.xml"), Set.of()), NO_ID);

		assertThat(plan.getUnits()).containsExactly("TemplateStore/Schemes/news/news");
		assertThat(plan.getWholeDirs()).containsExactly("Entities/news/article");
	}

	private GitChanges changes() {
		return new GitChanges("HEAD",
				Set.of("PageStore/folder/page/content/s1/data.xml", "PageStore/folder/StoreElement.xml", "Entities/myschema/news/Entities.xml",
						"Global/Project/Project.xml", ".FirstSpirit/Files_localhost_15.txt", "loose.txt"),
				Set.of("PageStore/folder/page/content/s2/StoreElement.xml", "PageStore/folder/gone/StoreElement.xml"));
	}

	private void element(final String dir, final String tag, final String fsInfo) throws IOException {
		file(dir + "/StoreElement.xml", "<" + tag + " id=\"" + Math.abs(dir.hashCode()) + "\" name=\"" + dir.substring(dir.lastIndexOf('/') + 1) + "\"/>");
		file(dir + "/FS_Info.txt", fsInfo);
		file(dir + "/FS_Files.txt", "");
	}

	private void file(final String path, final String content) throws IOException {
		final Path file = syncDir.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private String read(final String path) throws IOException {
		return Files.readString(target.resolve(path), StandardCharsets.UTF_8);
	}
}
