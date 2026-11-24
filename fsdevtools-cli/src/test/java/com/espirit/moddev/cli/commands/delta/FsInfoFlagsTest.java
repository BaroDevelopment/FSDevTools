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

import static org.assertj.core.api.Assertions.assertThat;

class FsInfoFlagsTest {

	private static final String FULL = """
			AllChildren: 1
			ExportPermissions: NoPermissions
			ExportVersion: 3.2
			HierarchicElement: true
			Language_EN: English
			""";

	private static final String PARTIAL_PARENT = """
			ExportPermissions: NoPermissions
			ExportVersion: 3.2
			ExtraElement: true
			HierarchicElement: true
			Language_EN: English
			""";

	@Test
	void readsFlags() {
		assertThat(FsInfoFlags.hasAllChildren(FULL)).isTrue();
		assertThat(FsInfoFlags.hasExtraElement(FULL)).isFalse();
		assertThat(FsInfoFlags.hasAllChildren(PARTIAL_PARENT)).isFalse();
		assertThat(FsInfoFlags.hasExtraElement(PARTIAL_PARENT)).isTrue();
	}

	@Test
	void restoresFullExportFlagsAtSortedPositions() {
		assertThat(FsInfoFlags.withFlags(PARTIAL_PARENT, true, false)).isEqualTo(FULL);
	}

	@Test
	void writesPartialExportFlagsAtSortedPositions() {
		assertThat(FsInfoFlags.withFlags(FULL, false, true)).isEqualTo(PARTIAL_PARENT);
	}

	@Test
	void keepsSectionsAfterTheHeader() {
		final String root = "ExportVersion: 3.2\nExtraElement: true\n\n[self]\nuid=root\nAllChildren: 1\n";
		assertThat(FsInfoFlags.withFlags(root, true, false)).isEqualTo("AllChildren: 1\nExportVersion: 3.2\n\n[self]\nuid=root\nAllChildren: 1\n");
	}

	@Test
	void keepsWindowsLineSeparatorsAndMissingTrailingSeparator() {
		assertThat(FsInfoFlags.withFlags("ExportVersion: 3.2\r\nLanguage_EN: English", true, true))
				.isEqualTo("AllChildren: 1\r\nExportVersion: 3.2\r\nExtraElement: true\r\nLanguage_EN: English");
	}

	@Test
	void appendsFlagsThatSortLast() {
		assertThat(FsInfoFlags.withFlags("AAA: 1\n", true, true)).isEqualTo("AAA: 1\nAllChildren: 1\nExtraElement: true\n");
	}
}
