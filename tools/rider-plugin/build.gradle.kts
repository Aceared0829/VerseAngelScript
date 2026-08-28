import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.verseangelscript"
version = "0.5.6"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

val configuredRiderPath = providers.gradleProperty("riderPath")
    .orElse(providers.environmentVariable("RIDER_HOME"))

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.14.4")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.14.4")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-test:2.4.0")
    // The Rider framework creates a real Solution Host. LightPlatformTestCase
    // alone cannot initialize Rider's frontend/backend solution session.
    testImplementation("com.jetbrains.intellij.rider:rider-test-framework-core:262.8665.401") {
        isTransitive = false
    }
    testImplementation("com.jetbrains.intellij.rider:rider-test-framework:262.8665.401") {
        isTransitive = false
    }
    testImplementation("com.jetbrains.intellij.rider:rider-test-framework-junit:262.8665.401") {
        isTransitive = false
    }
    testImplementation("com.jetbrains.intellij.rider:rider-test-framework-integration-junit:262.8665.401") {
        isTransitive = false
    }
    testRuntimeOnly("com.jetbrains.intellij.platform:ijent-test-framework:262.8665.401") {
        isTransitive = false
    }

    intellijPlatform {
        if (configuredRiderPath.isPresent) {
            local(configuredRiderPath.get())
        } else {
            rider("2026.2.0.2") {
                useInstaller = false
            }
            jetbrainsRuntime()
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
            Adds a Rider Solution Host integration test for nested include and declaration
            navigation, while retaining the strict .vas entry/include validation.
        """.trimIndent()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks {
    test {
        useJUnitPlatform()
    }

    withType<JavaCompile> {
        options.release = 25
        options.encoding = "UTF-8"
    }

}
