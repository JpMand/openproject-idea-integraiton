import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.intellij.platform")
    id("org.jetbrains.changelog")
}

/** Maps an IDE version ("2026.2.3") or build number ("263.7307.112") to its branch number (262, 263). */
fun platformBranchOf(version: String): Int {
    val parts = version.substringBefore('-').split('.')
    return if (parts[0].length == 4) "${parts[0].takeLast(2)}${parts[1]}".toInt() else parts[0].toInt()
}

val platformVersion = providers.gradleProperty("platformVersion").get()
val platformBranch = platformBranchOf(platformVersion)

// "Issue Trackers" (com.intellij.tasks) is unbundled since 2026.2 and published per IDE branch.
val tasksPluginVersion = providers.gradleProperty("tasksPluginVersion")
    .orElse(providers.gradleProperty("tasksPluginVersion.$platformBranch"))
    .get()

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Ktor itself comes from the IDE; only the mock engine is added for tests.
    testImplementation("io.ktor:ktor-client-mock-jvm:3.4.1") { isTransitive = false }

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(platformVersion)

        plugin("com.intellij.tasks", tasksPluginVersion)
        if (platformBranch >= 263) {
            // 2026.3 moved the Tasks and VCS modules into bundled plugins.
            bundledPlugins("com.intellij.platform.tasks", "com.intellij.platform.vcs")
        } else {
            bundledModules("intellij.platform.tasks", "intellij.platform.tasks.impl", "intellij.platform.vcs.impl")
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
            untilBuild = providers.gradleProperty("pluginUntilBuild")
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

intellijPlatformTesting {
    runIde {
        // Manual checks against the IntelliJ IDEA 2026.3 RC: ./gradlew runIdeRc
        register("runIdeRc") {
            type = IntelliJPlatformType.IntellijIdea
            version = providers.gradleProperty("rcPlatformVersion")
            plugins {
                plugin("com.intellij.tasks", providers.gradleProperty("tasksPluginVersion.263").get())
            }
        }
    }
}
