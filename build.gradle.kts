import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

/** Maps an IDE version ("2026.2.3") or build number ("263.7307.200") to its branch number (262, 263). */
fun platformBranchOf(version: String): Int {
    val parts = version.substringBefore('-').split('.')
    return if (parts[0].length == 4) "${parts[0].takeLast(2)}${parts[1]}".toInt() else parts[0].toInt()
}

val platformVersion = providers.gradleProperty("platformVersion").get()
val platformBranch = platformBranchOf(platformVersion)

// "Issue Trackers" (com.intellij.tasks) is bundled up to 2026.1; since 2026.2 it is published per IDE branch.
val tasksPluginVersion: String? = providers.gradleProperty("tasksPluginVersion")
    .orElse(providers.gradleProperty("tasksPluginVersion.$platformBranch"))
    .orNull

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Ktor itself comes from the IDE; only the mock engine is added for tests.
    testImplementation("io.ktor:ktor-client-mock-jvm:3.4.1") { isTransitive = false }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(platformVersion)

        if (platformBranch >= 262) {
            plugin("com.intellij.tasks", requireNotNull(tasksPluginVersion) { "No tasksPluginVersion.$platformBranch in gradle.properties" })
        } else {
            bundledPlugin("com.intellij.tasks")
        }
        if (platformBranch >= 263) {
            // 2026.3 moved the Tasks modules into a bundled plugin.
            bundledPlugin("com.intellij.platform.tasks")
        } else {
            bundledModules("intellij.platform.tasks", "intellij.platform.tasks.impl")
        }
        // Ktor client and kotlinx.serialization are provided by the IDE; never bundle them.
        bundledModule("intellij.libraries.ktor.client")

        testFramework(TestFrameworkType.Platform)
        testBundledPlugins("com.intellij.modules.ultimate")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // No until-build: the plugin stays installable on newer IDE releases.
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            providers.gradleProperty("pluginVerificationIdes").get().split(',').map { it.trim() }.forEach {
                create(IntelliJPlatformType.IntellijIdea, it)
            }
        }
    }
}

// With -PintegrationTests the test task runs the *IntegrationTest classes against the OpenProject at
// OPENPROJECT_IT_URL instead of the unit tests: scripts/openproject-it.sh test
val integrationTests = providers.gradleProperty("integrationTests").map { it != "false" }.getOrElse(false)

tasks {
    processResources {
        // Stamps the plugin version for the User-Agent header (core/OpenProjectClientPool).
        val pluginVersion = project.version.toString()
        inputs.property("pluginVersion", pluginVersion)
        filesMatching("**/core/plugin.properties") {
            expand("version" to pluginVersion)
        }
    }

    test {
        filter {
            if (integrationTests) includeTestsMatching("*IntegrationTest") else excludeTestsMatching("*IntegrationTest")
        }
        if (integrationTests) {
            environment("OPENPROJECT_IT_URL", providers.environmentVariable("OPENPROJECT_IT_URL").getOrElse(""))
            environment("OPENPROJECT_IT_TOKEN", providers.environmentVariable("OPENPROJECT_IT_TOKEN").getOrElse(""))
            outputs.upToDateWhen { false }
            testLogging {
                events("passed", "skipped", "failed")
                showStandardStreams = true
            }
        }
    }
}
