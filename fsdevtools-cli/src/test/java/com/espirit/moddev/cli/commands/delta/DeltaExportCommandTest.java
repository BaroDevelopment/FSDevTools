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

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DeltaExportCommandTest {

	@Test
	void parsesSinceDates() {
		assertThat(DeltaExportCommand.parseDate("2026-11-23T14:30:00Z")).isEqualTo(Instant.parse("2026-11-23T14:30:00Z"));
		assertThat(DeltaExportCommand.parseDate("2026-11-23T14:30:00+01:00")).isEqualTo(Instant.parse("2026-11-23T13:30:00Z"));
		assertThat(DeltaExportCommand.parseDate("2026-11-23T14:30"))
				.isEqualTo(LocalDateTime.of(2026, 11, 23, 14, 30).atZone(ZoneId.systemDefault()).toInstant());
		assertThat(DeltaExportCommand.parseDate(" 2026-11-23 "))
				.isEqualTo(LocalDate.of(2026, 11, 23).atStartOfDay(ZoneId.systemDefault()).toInstant());
	}

	@Test
	void rejectsUnparsableDates() {
		assertThatThrownBy(() -> DeltaExportCommand.parseDate("23.11.2026")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("23.11.2026");
	}
}
