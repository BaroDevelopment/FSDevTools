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

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * The files of a sync directory that differ from a git revision. Paths are relative to the sync directory.
 */
public final class GitChanges {

	private static final long TIMEOUT_MINUTES = 10;

	private final String _base;
	private final Set<String> _changed;
	private final Set<String> _deleted;

	/**
	 * Creates a new instance.
	 *
	 * @param base    the commit the files were compared with
	 * @param changed the added, modified or untracked files
	 * @param deleted the deleted files
	 */
	public GitChanges(@NotNull final String base, @NotNull final Set<String> changed, @NotNull final Set<String> deleted) {
		_base = base;
		_changed = Collections.unmodifiableSet(new TreeSet<>(changed));
		_deleted = Collections.unmodifiableSet(new TreeSet<>(deleted));
	}

	/**
	 * Gets the commit the files were compared with: the merge base of the requested revision and {@code HEAD}.
	 *
	 * @return the commit id
	 */
	@NotNull
	public String getBase() {
		return _base;
	}

	/**
	 * Gets the added, modified or untracked files.
	 *
	 * @return the files
	 */
	@NotNull
	public Set<String> getChanged() {
		return _changed;
	}

	/**
	 * Gets the deleted files.
	 *
	 * @return the files
	 */
	@NotNull
	public Set<String> getDeleted() {
		return _deleted;
	}

	/**
	 * Checks if no file changed.
	 *
	 * @return true if nothing changed
	 */
	public boolean isEmpty() {
		return _changed.isEmpty() && _deleted.isEmpty();
	}

	/**
	 * Asks git which files of the sync directory differ from a revision, including uncommitted and untracked files.
	 * <p>
	 * The files are compared with the merge base of the revision and {@code HEAD}, so that for a branch such as
	 * {@code origin/master} only the changes of the current branch count, not those made on the other branch since.
	 *
	 * @param syncDir the sync directory inside a git work tree
	 * @param ref     the git revision, for example {@code HEAD}
	 * @return the changes
	 * @throws IOException if git fails or the revision is unknown
	 */
	@NotNull
	public static GitChanges detect(@NotNull final Path syncDir, @NotNull final String ref) throws IOException {
		if (ref.isBlank() || ref.startsWith("-")) {
			throw new IllegalArgumentException("Invalid git revision '" + ref + "'.");
		}
		run(syncDir, "rev-parse", "--is-inside-work-tree");
		final String commit;
		try {
			commit = run(syncDir, "rev-parse", "--verify", "--quiet", ref + "^{commit}").trim();
		} catch (final IOException e) {
			throw new IOException("Unknown git revision '" + ref + "' in '" + syncDir + "'.", e);
		}
		final String base;
		try {
			base = run(syncDir, "merge-base", commit, "HEAD").trim();
		} catch (final IOException e) {
			throw new IOException("Git revision '" + ref + "' and HEAD have no common history in '" + syncDir + "'.", e);
		}
		final Set<String> changed = new TreeSet<>();
		final Set<String> deleted = new TreeSet<>();
		// -z: NUL separated, no quoting; --relative: paths relative to (and limited to) the sync directory
		final List<String> diff = split(run(syncDir, "diff", "--name-status", "--no-renames", "--relative", "-z", base, "--"));
		for (int i = 0; i + 1 < diff.size(); i += 2) {
			final String status = diff.get(i);
			final String path = diff.get(i + 1);
			if (status.startsWith("D")) {
				deleted.add(path);
			} else {
				changed.add(path);
			}
		}
		changed.addAll(split(run(syncDir, "ls-files", "--others", "--exclude-standard", "-z")));
		return new GitChanges(base, changed, deleted);
	}

	/**
	 * Reads a file as it was in a git revision.
	 *
	 * @param syncDir the sync directory
	 * @param ref     the git revision
	 * @param path    the file, relative to the sync directory
	 * @return the file content
	 * @throws IOException if git fails
	 */
	@NotNull
	public static byte[] readFile(@NotNull final Path syncDir, @NotNull final String ref, @NotNull final String path) throws IOException {
		return runBytes(syncDir, "show", ref + ":./" + path);
	}

	@NotNull
	private static List<String> split(@NotNull final String output) {
		final List<String> parts = new ArrayList<>();
		for (final String part : output.split("\0")) {
			if (!part.isEmpty()) {
				parts.add(part);
			}
		}
		return parts;
	}

	@NotNull
	private static String run(@NotNull final Path dir, @NotNull final String... args) throws IOException {
		return new String(runBytes(dir, args), StandardCharsets.UTF_8);
	}

	@NotNull
	private static byte[] runBytes(@NotNull final Path dir, @NotNull final String... args) throws IOException {
		final List<String> command = new ArrayList<>();
		command.add("git");
		command.add("-c");
		command.add("core.quotepath=off");
		Collections.addAll(command, args);
		final Process process;
		try {
			process = new ProcessBuilder(command).directory(dir.toFile()).start();
		} catch (final IOException e) {
			throw new IOException("Cannot run git - is it installed and on the PATH?", e);
		}
		try {
			process.getOutputStream().close();
			// both streams are drained in the background, so that a full pipe cannot block git and the timeout applies
			final CompletableFuture<byte[]> stdout = CompletableFuture.supplyAsync(() -> readFully(process.getInputStream()));
			final CompletableFuture<byte[]> stderr = CompletableFuture.supplyAsync(() -> readFully(process.getErrorStream()));
			if (!process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
				process.destroyForcibly();
				throw new IOException("git " + String.join(" ", args) + " did not finish within " + TIMEOUT_MINUTES + " minutes.");
			}
			final String errorOutput = new String(stderr.get(), StandardCharsets.UTF_8).trim();
			if (process.exitValue() != 0) {
				throw new IOException("git " + String.join(" ", args) + " failed in '" + dir + "' (exit code " + process.exitValue() + ")"
						+ (errorOutput.isEmpty() ? "" : ": " + errorOutput));
			}
			return stdout.get();
		} catch (final InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
			throw new IOException("Interrupted while running git.", e);
		} catch (final ExecutionException e) {
			throw new IOException("Cannot read the output of git.", e.getCause());
		}
	}

	@NotNull
	private static byte[] readFully(@NotNull final InputStream in) {
		try {
			return in.readAllBytes();
		} catch (final IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
