import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.verseangelscript"
version = "0.5.2"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val configuredRiderPath = providers.gradleProperty("riderPath")
    .orElse(providers.environmentVariable("RIDER_HOME"))

dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        if (configuredRiderPath.isPresent) {
            local(configuredRiderPath.get())
        } else {
            rider("2026.2.0.2")
        }
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = "Verse AngelScript Language Support"
        version = project.version.toString()

        ideaVersion {
            sinceBuild = "262"
            untilBuild = "262.*"
        }

        description = """
            Rider language support for Verse AngelScript (.vas), including project symbol
            indexing, cross-file completion, include/declaration/usage/implementation
            navigation, project templates, and integrated vasbuild/vasrun tools.
        """.trimIndent()

        changeNotes = """
            Fixes VAS PSI creation so navigation, completion, Find Usages, rename, and
            Code Vision work at runtime. Also adds a dedicated inheritance index for
            responsive implementation and inheritor navigation.
        """.trimIndent()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks {
    withType<JavaCompile> {
        options.release = 25
        options.encoding = "UTF-8"
    }

}
