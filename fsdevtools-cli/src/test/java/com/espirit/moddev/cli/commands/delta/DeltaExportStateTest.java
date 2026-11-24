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
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeltaExportStateTest {

	@TempDir
	Path syncDir;

	@Test
	void stateFileIsNamedAfterServerAndProject() {
		assertThat(DeltaExportState.stateFile(syncDir, "fs.example.com:8000", 15))
				.isEqualTo(syncDir.resolve(".FirstSpirit/fs-cli-delta-export_fs.example.com_8000_15.properties"));
	}

	@Test
	void writesAndReadsTheState() throws IOException {
		final Path file = DeltaExportState.stateFile(syncDir, "localhost", 15);
		assertThat(DeltaExportState.read(file)).isNull();

		DeltaExportState.write(file, 4711, 1700000000000L, 15, "Smart Living", "localhost", true);

		assertThat(DeltaExportState.read(file)).isEqualTo(new DeltaExportState.Stored(4711, true));
		assertThat(Files.readString(file, StandardCharsets.UTF_8))
				.contains("revision=4711", "projectName=Smart Living", "releaseState=true")
				.doesNotContainPattern("#.*\\d{2}:\\d{2}:\\d{2}");
	}

	@Test
	void doesNotRewriteAnUnchangedState() throws IOException {
		final Path file = DeltaExportState.stateFile(syncDir, "localhost", 15);
		DeltaExportState.write(file, 1, 2, 15, "p", "localhost", false);
		final FileTime old = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));
		Files.setLastModifiedTime(file, old);

		DeltaExportState.write(file, 1, 2, 15, "p", "localhost", false);
		assertThat(Files.getLastModifiedTime(file)).isEqualTo(old);

		DeltaExportState.write(file, 2, 3, 15, "p", "localhost", false);
		assertThat(DeltaExportState.read(file)).isEqualTo(new DeltaExportState.Stored(2, false));
	}

	@Test
	void rejectsAStateWithoutRevision() throws IOException {
		final Path file = DeltaExportState.stateFile(syncDir, "localhost", 15);
		Files.createDirectories(file.getParent());
		Files.writeString(file, "projectId=15\n");
		assertThatThrownBy(() -> DeltaExportState.read(file)).isInstanceOf(IOException.class).hasMessageContaining("revision");
	}
}
