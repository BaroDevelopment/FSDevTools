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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeltaImportStateTest {

	@TempDir
	Path syncDir;

	@Test
	void stateFileIsNamedAfterServerAndProject() {
		assertThat(DeltaImportState.stateFile(syncDir, "localhost-8000", 15))
				.isEqualTo(syncDir.resolve(".FirstSpirit/fs-cli-delta-import_localhost-8000_15.properties"));
	}

	@Test
	void writesAndReadsTheRevisionsNewerThanTheLastExport() throws IOException {
		final Path file = DeltaImportState.stateFile(syncDir, "localhost-8000", 15);
		final String page = DeltaImportState.key(Store.Type.PAGESTORE, -3448106719415394966L);
		final String media = DeltaImportState.key(Store.Type.MEDIASTORE, 42);

		DeltaImportState.write(file, Map.of(page, 120L, media, 90L), 100);

		assertThat(DeltaImportState.read(file)).containsExactly(Map.entry(page, 120L));
	}

	@Test
	void readsNothingWithoutAFileAndRejectsInvalidRevisions() throws IOException {
		final Path file = DeltaImportState.stateFile(syncDir, "localhost-8000", 15);
		assertThat(DeltaImportState.read(file)).isEmpty();

		Files.createDirectories(file.getParent());
		Files.writeString(file, "PAGESTORE.1=abc\n");
		assertThatThrownBy(() -> DeltaImportState.read(file)).isInstanceOf(IOException.class).hasMessageContaining("PAGESTORE.1");
	}
}
