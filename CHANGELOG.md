<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# openproject-idea-integraiton Changelog

## [Unreleased]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
- Build setup for IntelliJ IDEA 2026.2 and 2026.3, depending on the "Issue Trackers" (Tasks) plugin
- OpenProject API v3 client (HAL+JSON) on the IDE-bundled Ktor client and kotlinx.serialization
- Integration tests against OpenProject in Docker (scripts/openproject-it.sh)
- "OpenProject" task server for Tools | Tasks & Contexts: search (open/closed, assigned to me, project, saved query), state changes and time tracking
- Work package list and work package editor tabs (restored after restart), Log Time dialog, active task tracking and actions in Tools | OpenProject
- Work package table: configurable columns (drag, Configure View), header sorting, search, open/closed and "assigned to me" quick filters, filter bar with all server filters, saved queries, paging, active task banner
