# OpenProject Integration for IntelliJ IDEA

![Build](https://github.com/JpMand/openproject-idea-integraiton/workflows/Build/badge.svg)

Work with [OpenProject](https://www.openproject.org) work packages without leaving the IDE:

- **OpenProject task server** for <kbd>Tools</kbd> > <kbd>Tasks & Contexts</kbd>: find work packages in *Open Task*
  (open/closed, assigned to me, project, saved query, full-text search), switch tasks, change the status when closing
  a task, and post time spent.
- **Work package table** in an editor tab, like the table in OpenProject: configurable columns (order and visibility),
  sorting by column headers, a global search box, Open/Closed/All and "assigned to me" quick filters, a filter bar
  with every filter the server offers (status, assignee, dates, custom fields, …), saved queries and paging.
- **Work package page** in an editor tab: edit the subject, status, description (Markdown with preview), assignee and
  priority; read the activity and add comments; add and remove relations; upload (also by drag & drop), download,
  open and delete files.
- **Time logging** with a *Log Time* dialog (date, duration such as `1h 30m`, activity, comment).
- **Active task at a glance**: the work package of the active task is shown in the status bar (`OP #123`, click to
  open it), as a banner above the work package table and as a highlighted row.

Server address and API token are configured once, in the task server settings; the table, the work package page and
time logging all use them.

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
4. Optionally limit *Open Task* to one project or a saved query, to work packages assigned to you, or switch to
   full-text search (descriptions and comments as well as ids and subjects).

The token is kept in the IDE's password safe, never in project files. Proxy settings and trusted certificates are
taken from the IDE (<kbd>Settings</kbd> > <kbd>Appearance & Behavior</kbd> > <kbd>System Settings</kbd> >
<kbd>HTTP Proxy</kbd> / <kbd>Server Certificates</kbd>).

## Usage

| What | Where |
|---|---|
| Open the work package table | <kbd>Tools</kbd> > <kbd>OpenProject</kbd> > <kbd>Work Packages…</kbd>, or the *Tasks & Contexts* menu |
| Open a work package | double-click or <kbd>Enter</kbd> in the table, *Open Task* search, or the status bar widget |
| Start working on a work package | *Start Working* in the table's context menu or on the work package page (activates it as a task) |
| Log time | <kbd>Tools</kbd> > <kbd>OpenProject</kbd> > <kbd>Log Time…</kbd>, or the table / work package page toolbar |
| Configure columns and sorting | *Configure View…* above the table, drag column headers, click headers to sort (<kbd>Shift</kbd>+click adds a secondary sort) |
| Filter | search box (`#123` finds a work package by id), quick filters, *+ Add filter* in the filter bar |

Editor tabs are restored when the project is reopened, and the table's columns, sorting and filters are remembered
per server.

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
`./gradlew test -PintegrationTests` yourself or point a `runIde` sandbox at the same instance (user `admin`).

### Project layout

| Package | Contents |
|---|---|
| `api` | HAL+JSON client for OpenProject API v3 on the IDE-bundled Ktor client and kotlinx.serialization |
| `core` | server registry (from the task servers), connections and caches, time entries, active task tracking |
| `tasks` | the *OpenProject* task server type, its settings editor and task search |
| `vfs` | virtual files behind the editor tabs |
| `ui` | work package table (`ui.list`), work package page (`ui.detail`), status bar widget |
| `actions` | menu and toolbar actions, *Log Time* dialog |

---
Plugin based on the [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
