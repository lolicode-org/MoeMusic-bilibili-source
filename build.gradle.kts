import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("idea")
}

version = providers.gradleProperty("version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
    mavenCentral()
    maven {
        name = "MoeMusic on Codeberg"
        url = uri("https://codeberg.org/api/packages/lolicode/maven")
        content { includeGroupByRegex("org\\.lolicode.*") }
    }
    mavenLocal()
}

dependencies {
    compileOnly("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")

    testImplementation(kotlin("test"))
    testImplementation("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

idea {
    module {
        isDownloadSources = true
        isDownloadJavadoc = true
    }
}

val archiveProjectName = project.name
tasks.jar {
    inputs.property("projectName", archiveProjectName)
    from("LICENSE") { rename { "${it}_$archiveProjectName" } }
}
