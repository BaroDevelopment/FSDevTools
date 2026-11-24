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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ExportedFilesTest {

	@TempDir
	Path exportDir;

	@TempDir
	Path syncDir;

	@Test
	void movesTheFilesAndMergesTheBookkeeping() throws IOException {
		write(syncDir, "PageStore/StoreElement.xml", "<PAGESTORE id=\"1\"/>");
		write(syncDir, "PageStore/page/StoreElement.xml", "<PAGE id=\"2\" name=\"page\"/>");
		write(syncDir, "PageStore/page/data.xml", "old");
		write(syncDir, "PageStore/other/StoreElement.xml", "<PAGE id=\"3\" name=\"other\"/>");
		write(syncDir, ".FirstSpirit/Files_localhost_15.txt", "[61]\nroot old\n\n[111]\npage old\n\n[112]\nother\n");
		write(exportDir, "PageStore/StoreElement.xml", "<PAGESTORE id=\"1\"/>");
		write(exportDir, "PageStore/page/StoreElement.xml", "<PAGE id=\"2\" name=\"page\"/>");
		write(exportDir, "PageStore/page/data.xml", "new");
		write(exportDir, "PageStore/new/StoreElement.xml", "<PAGE id=\"4\" name=\"new\"/>");
		write(exportDir, ".FirstSpirit/Files_localhost_15.txt", "[61]\nroot new\n\n[111]\npage new\n\n[113]\nnew\n");
		write(exportDir, ".FirstSpirit/Import_localhost_15.txt", "# ExportTime: 2\n\n[61]\n");

		assertThat(ExportedFiles.moveInto(exportDir, syncDir)).isTrue();

		assertThat(read(syncDir, "PageStore/page/data.xml")).isEqualTo("new");
		assertThat(read(syncDir, "PageStore/new/StoreElement.xml")).isEqualTo("<PAGE id=\"4\" name=\"new\"/>");
		assertThat(read(syncDir, "PageStore/other/StoreElement.xml")).isEqualTo("<PAGE id=\"3\" name=\"other\"/>");
		assertThat(read(syncDir, ".FirstSpirit/Files_localhost_15.txt")).isEqualTo("[61]\nroot new\n\n[111]\npage new\n\n[112]\nother\n\n[113]\nnew\n\n");
		assertThat(read(syncDir, ".FirstSpirit/Import_localhost_15.txt")).isEqualTo("# ExportTime: 2\n\n[61]\n");
	}

	@Test
	void movesNothingIfAFolderBelongsToAnotherElement() throws IOException {
		write(syncDir, "PageStore/page/StoreElement.xml", "<PAGE id=\"2\" name=\"page\"/>");
		write(syncDir, "PageStore/page/data.xml", "old");
		write(exportDir, "PageStore/page/StoreElement.xml", "<PAGE id=\"9\" name=\"page\"/>");
		write(exportDir, "PageStore/page/data.xml", "new");

		assertThat(ExportedFiles.moveInto(exportDir, syncDir)).isFalse();

		assertThat(read(syncDir, "PageStore/page/data.xml")).isEqualTo("old");
	}

	private static void write(final Path dir, final String path, final String content) throws IOException {
		final Path file = dir.resolve(path);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private static String read(final Path dir, final String path) throws IOException {
		return Files.readString(dir.resolve(path), StandardCharsets.UTF_8);
	}
}
