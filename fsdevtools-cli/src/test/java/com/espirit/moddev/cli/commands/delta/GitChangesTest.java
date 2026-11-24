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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class GitChangesTest {

	@TempDir
	Path repo;

	private Path syncDir;

	@BeforeEach
	void createRepository() throws Exception {
		assumeTrue(gitAvailable(), "git is not installed");
		syncDir = Files.createDirectories(repo.resolve("src/main/firstspirit"));
		git("init", "-q");
		write("PageStore/page/StoreElement.xml", "v1");
		write("PageStore/page/data.xml", "data");
		write("PageStore/äöü page/StoreElement.xml", "umlaut");
		write("../../../README.md", "outside the sync dir");
		git("add", "-A");
		git("-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-m", "base");
	}

	@Test
	void reportsCommittedUncommittedAndUntrackedChangesBelowTheSyncDir() throws Exception {
		write("PageStore/page/StoreElement.xml", "v2");
		Files.delete(syncDir.resolve("PageStore/page/data.xml"));
		write("PageStore/new/StoreElement.xml", "new");
		write("PageStore/äöü page/StoreElement.xml", "umlaut v2");
		write("../../../README.md", "changed outside");

		final GitChanges changes = GitChanges.detect(syncDir, "HEAD");

		assertThat(changes.getChanged()).containsExactlyInAnyOrder("PageStore/page/StoreElement.xml", "PageStore/new/StoreElement.xml",
				"PageStore/äöü page/StoreElement.xml");
		assertThat(changes.getDeleted()).containsExactly("PageStore/page/data.xml");
		assertThat(GitChanges.readFile(syncDir, "HEAD", "PageStore/page/data.xml")).isEqualTo("data".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void comparesWithOlderCommits() throws Exception {
		write("PageStore/page/StoreElement.xml", "v2");
		git("-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-a", "-m", "second");

		assertThat(GitChanges.detect(syncDir, "HEAD").isEmpty()).isTrue();
		assertThat(GitChanges.detect(syncDir, "HEAD~1").getChanged()).containsExactly("PageStore/page/StoreElement.xml");
	}

	@Test
	void comparesWithTheMergeBaseOfABranch() throws Exception {
		git("branch", "other");
		git("checkout", "-q", "other");
		write("PageStore/page/data.xml", "changed on the other branch");
		git("-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-a", "-m", "other");
		git("checkout", "-q", "-");
		write("PageStore/page/StoreElement.xml", "v2");
		git("-c", "user.email=test@example.com", "-c", "user.name=test", "commit", "-q", "-a", "-m", "mine");

		final GitChanges changes = GitChanges.detect(syncDir, "other");

		assertThat(changes.getChanged()).containsExactly("PageStore/page/StoreElement.xml");
		assertThat(changes.getDeleted()).isEmpty();
		assertThat(GitChanges.readFile(syncDir, changes.getBase(), "PageStore/page/data.xml")).isEqualTo("data".getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void rejectsUnknownRevisions() {
		assertThatThrownBy(() -> GitChanges.detect(syncDir, "does-not-exist")).isInstanceOf(IOException.class).hasMessageContaining("does-not-exist");
		assertThatThrownBy(() -> GitChanges.detect(syncDir, "--output=x")).isInstanceOf(IllegalArgumentException.class);
	}

	private void write(final String path, final String content) throws IOException {
		final Path file = syncDir.resolve(path).normalize();
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}

	private void git(final String... args) throws Exception {
		final List<String> command = new ArrayList<>(List.of("git"));
		command.addAll(List.of(args));
		final Process process = new ProcessBuilder(command).directory(repo.toFile()).redirectErrorStream(true).start();
		final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		if (!process.waitFor(1, TimeUnit.MINUTES) || process.exitValue() != 0) {
			throw new IllegalStateException("git " + String.join(" ", args) + " failed: " + output);
		}
	}

	private static boolean gitAvailable() {
		try {
			return new ProcessBuilder("git", "--version").start().waitFor(1, TimeUnit.MINUTES);
		} catch (final IOException e) {
			return false;
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}
}
