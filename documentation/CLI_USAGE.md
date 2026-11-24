# Command Line Interface Usage

## Introduction

Most of the information you need in order to use the command line tool can be found via the integrated help command `fs-cli help`:

![display command line interface help](images/display_cli_help.png)

## How to read the online help

### Base structure

The online help is devided in three main sections with an optional fourth example section:

- Name (command name and short command description)
- Synopsis (command syntax)
- Options (description of the command options and their vaules)
- Examples (command examples; not shown below)

![three parts: name, synopsis and options](images/help_structure.png)

### Syntax

In the *SYNOPSIS* section the command syntax is explained. 
While surroundings by
- *square brackets* like **[ ... ]** indicate **optional** command elements,
- *curly braces and pipes* like **{ ... | ... }** list **alternative** command elements
- `plain text` are **required** command elements, which must be provided in the order of the specified syntax.

In general, the command line interface offers a set of sub commands, that can be grouped. 
A command can be configured with options and additional global options. 
The global options have to be stated first and before the command, while the command's options have to be stated after the command itself. 
The order of options and global options within their regions is arbitrary.

![three parts: name, synopsis and options](images/help_syntax.png)

While groups behave mostly like commands (because a default command is executed if specified for a group) the description of a group might give information about several commands.
In order to get information about all known commands or command groups use `fs-cli help`.
Information about a command or a group can be retrieved with `fs-cli help commandogroup`. 
Besides that, examples are provided per command.
Every command description also features information about the command's specific options and all global options.

Exampe online help for test command group (enter `fs-cli help test`):

![three parts: name, synopsis and options](images/help_command_group.png)

## Delta export and import

`exportDelta` and `importDelta` transfer only what changed instead of the whole project.
Both work on a sync directory that a full `export` filled before.

### exportDelta

```
fs-cli -sd src/main/firstspirit exportDelta [--dryRun] [--sinceRevision <id> | --sinceDate <date>] [--useReleaseState] [-- <identifiers>]
```

Exports the elements changed on the server since the last run, with everything below them (the page of a changed section, a template, a media, ...), and the changed datasets.
A changed SiteStore element is exported together with its neighbours, because FirstSpirit writes their order only between elements of the same export.
Removes the folders of elements that were deleted, moved or renamed on the server.
The revision is recorded in `.FirstSpirit/fs-cli-delta-export_<host>-<port>_<project id>.properties`; commit it together with the export.
If `.FirstSpirit` is ignored by git, ignore its content instead and add an exception:

```
.FirstSpirit/*
!.FirstSpirit/fs-cli-delta-export_*.properties
```

The first run needs a starting point: `--sinceDate` with the time of the last full export, or `--sinceRevision`.
Project properties are not tracked by revisions; pass them as identifiers, for example `-- projectproperty:ALL`, to export them on every run.
If the export fails, the sync directory is restored and no revision is recorded.

### importDelta

```
fs-cli -sd src/main/firstspirit importDelta [--since <git revision>] [--dryRun] [--keepTempDir] [<import options>]
```

Imports the elements whose files differ from `--since` (default `HEAD`), including uncommitted and untracked files.
Files are compared at the point where `--since` and the current branch split, so `--since origin/master` imports only the changes of the current branch.
Requires `git` on the `PATH`.

An element is imported with everything below it, a changed folder with all its content.
Nothing is deleted on the server: elements deleted locally are only listed.
An element is skipped, with the reason, if
- it was changed, moved or deleted on the server since the last `exportDelta` or `importDelta`, so that the change is not overwritten,
- the server has elements below it that are missing locally, because the import would delete them,
- its folder matches more than one element on the server.

The server check needs an `exportDelta` state of the current state (not `--useReleaseState`); without one, the command says so.
It covers store elements, not datasets and project settings.
The import options of `import`, for example `-i <comment>`, work as well.
