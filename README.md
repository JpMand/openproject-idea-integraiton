# OpenProject Integration for IntelliJ IDEA

![Build](https://github.com/JpMand/openproject-idea-integration/workflows/Build/badge.svg)

Work with [OpenProject](https://www.openproject.org) work packages without leaving the IDE:

- **OpenProject task server** for <kbd>Tools</kbd> > <kbd>Tasks & Contexts</kbd>: find work packages in *Open Task*
  (open/closed, assigned to me, project, saved query, full-text search), switch tasks, change the status when closing
  a task, and post time spent.
- **OpenProject tool window** (bottom) with the work package table, like the table in OpenProject: configurable
  columns (order and visibility), sorting by column headers, a global search box, quick filter dropdowns for project,
  type, status, priority and assignee, a filter bar with every filter the server offers (dates, custom fields, …),
  saved queries and paging; % Complete is drawn as a progress bar. Lists start with the project configured for the
  task server.
- **More lists in tabs**: *Open as New Tab* keeps the current filters in a tab of their own; <kbd>+</kbd> after the
  last tab opens a fresh list.
- **Work package preview and tabs**: clicking a work package shows it next to the list; *Open in New Tab* opens it in a
  tab with subject, description and properties on the left and activity, relations and files on the right. Edit the
  subject, status, description (Markdown with preview), assignee and priority; add comments; add and remove
  relations; upload (also by drag & drop), download, open and delete files.
- **Time logging** with a *Log Time* dialog (date, duration such as `1h 30m`, activity, comment).
- **Active task at a glance**: the work package of the active task is shown in the status bar (`OP #123`, click to
  open it), as a banner above the work package table and as a highlighted row.

Server address, API token and project are configured once, in the task server settings; the tool window and time
logging use them.

## Requirements

- IntelliJ IDEA 2026.2 or 2026.3 (any edition).
- The **Issue Trackers** plugin (`com.intellij.tasks`), which provides *Tasks & Contexts*. It is no longer bundled
  with the IDE: install it from <kbd>Settings</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd>.
- **OpenProject 15 or later**. Features that depend on the server version (for example time entries on 15/16 versus
  17) are detected at runtime.
- An OpenProject **API token** (OAuth is not supported yet).

## Setup

1. In OpenProject, open <kbd>My account</kbd> > <kbd>Access tokens</kbd> and create an **API** token.
2. In the IDE, open <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Tasks</kbd> > <kbd>Servers</kbd>, click
   <kbd>+</kbd> and choose **OpenProject**.
3. Enter the server address (for example `https://openproject.example.com`) and the API token, then click
   <kbd>Test</kbd>.
4. Optionally choose a project: *Open Task* and new work package lists then show that project (the list's *Project*
   dropdown can still switch to another project or all projects). You can also limit *Open Task* to a saved query or
   to work packages assigned to you, or switch to full-text search (descriptions and comments as well as ids and
   subjects).

The token is kept in the IDE's password safe, never in project files. Proxy settings and trusted certificates are
taken from the IDE (<kbd>Settings</kbd> > <kbd>Appearance & Behavior</kbd> > <kbd>System Settings</kbd> >
<kbd>HTTP Proxy</kbd> / <kbd>Server Certificates</kbd>).

## Usage

| What | Where |
|---|---|
| Show the work package list | the **OpenProject** tool window button (bottom), or <kbd>View</kbd> > <kbd>Tool Windows</kbd> > <kbd>OpenProject</kbd> |
| Preview a work package | click it in the list, or right-click > *Open on the Right* |
| Open a work package in a tab | double-click or <kbd>Enter</kbd> in the list, *Open in New Tab* in the preview toolbar or the context menu, *Open Task* + *Open Active Work Package*, or the status bar widget |
| Another list | *Open as New Tab* in the list toolbar (current filters), or <kbd>+</kbd> after the last tab (new list) |
| Start working on a work package | *Start Working* in the context menu, the preview or the work package tab (activates it as a task) |
| Log time | <kbd>Tools</kbd> > <kbd>OpenProject</kbd> > <kbd>Log Time…</kbd>, or the list / preview / work package tab toolbar |
| Configure columns and sorting | *Configure View…* in the list toolbar, drag column headers, click headers to sort (<kbd>Shift</kbd>+click adds a secondary sort) |
| Filter | search box (`#123` finds a work package by id), the *Project*, *Type*, *Status*, *Priority* and *Assignee* dropdowns, the filter bar (*Filters* button, *+ Add filter*), saved queries in *View* |

Tool window tabs are restored when the project is reopened, and each list remembers its columns, sorting and filters.

If the IDE has the **Time Tracking** plugin (`com.intellij.tasks.timeTracking`, IntelliJ IDEA Ultimate only), posting
the time spent from it also logs the time in OpenProject. The *Log Time* dialog works in every edition.

## Development

```shell
./gradlew test                                      # unit and platform tests (IntelliJ IDEA 2026.2.3)
./gradlew test -PplatformVersion=263.7307.112       # the same against the IntelliJ IDEA 2026.3 RC
./gradlew verifyPlugin                              # Plugin Verifier on 2026.2.3 and the 2026.3 RC
./gradlew runIde                                    # sandbox IDE (2026.2.3) with Issue Trackers installed
./gradlew runIdeRc                                  # sandbox IDE on the 2026.3 RC
```

### Integration tests

The integration tests run against a real OpenProject in Docker:

```shell
scripts/openproject-it.sh test                      # start OpenProject 17, create an API token, run the tests
OP_IMAGE=openproject/openproject:15 OP_PORT=8081 OP_CONTAINER=op-it15 \
  scripts/openproject-it.sh test                    # the same against OpenProject 15
scripts/openproject-it.sh test -PplatformVersion=263.7307.112   # extra arguments go to Gradle
scripts/openproject-it.sh down                      # remove the container
```

The first start migrates and seeds the database and takes a few minutes. `scripts/openproject-it.sh env` prints the
`OPENPROJECT_IT_URL` / `OPENPROJECT_IT_TOKEN` exports, so you can run
`./gradlew test -PintegrationTests` yourself or point a `runIde` sandbox at the same instance (user `admin`, initial password `admin`).

### Project layout

| Package | Contents |
|---|---|
| `api` | HAL+JSON client for OpenProject API v3 on the IDE-bundled Ktor client and kotlinx.serialization |
| `core` | server registry (from the task servers), connections and caches, time entries, active task tracking |
| `tasks` | the *OpenProject* task server type, its settings editor and task search |
| `ui` | tool window and its tabs (`ui.toolwindow`), work package list (`ui.list`), work package preview and tab (`ui.detail`), status bar widget |
| `actions` | menu and toolbar actions, *Log Time* dialog |

---
Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
