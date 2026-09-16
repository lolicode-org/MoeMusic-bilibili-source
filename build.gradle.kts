import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
    id("idea")
}

version = providers.gradleProperty("version").get()
group = providers.gradleProperty("maven_group").get()

repositories {
    mavenLocal()
    mavenCentral()
    maven {
        name = "Fabric"
        url = uri("https://maven.fabricmc.net/")
        content {
            includeGroup("net.fabricmc")
        }
    }
    maven {
        name = "NeoForged"
        url = uri("https://maven.neoforged.net/releases")
        content {
            includeGroup("net.neoforged.fancymodloader")
        }
    }
    maven {
        name = "MinecraftForge"
        url = uri("https://maven.minecraftforge.net/")
        content {
            includeGroup("net.minecraftforge")
        }
    }
    maven {
        name = "Lolicode Releases"
        url = uri("https://maven.lolicode.org/releases")
        content {
            includeGroupByRegex("org\\.lolicode.*")
        }
    }
    maven {
        name = "Lolicode Snapshots"
        url = uri("https://maven.lolicode.org/snapshots")
        content {
            includeGroupByRegex("org\\.lolicode.*")
        }
    }
    maven {
        name = "GitHubPackages"
        url = uri("https://maven.pkg.github.com/lolicode-org/MoeMusic")
        credentials {
            username = providers.gradleProperty("gpr.user")
                .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                .orElse("")
                .get()
            password = providers.gradleProperty("gpr.key")
                .orElse(providers.environmentVariable("GITHUB_PACKAGES_TOKEN"))
                .orElse(providers.environmentVariable("PACKAGES_READ_TOKEN"))
                .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                .orElse("")
                .get()
        }
        content {
            includeGroupByRegex("org\\.lolicode.*")
        }
    }
}

val platformSourceSet = sourceSets.create("platform") {
    compileClasspath += sourceSets.named("main").get().output
    runtimeClasspath += sourceSets.named("main").get().output
}

dependencies {
    compileOnly("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")

    // Platform sourceSet: contains modloader bootstrap entrypoints.
    // Isolated so main plugin code cannot accidentally use loader classes or their dependencies.
    "platformCompileOnly"(sourceSets["main"].output)
    "platformCompileOnly"("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")
    "platformCompileOnly"("net.fabricmc:fabric-loader:${providers.gradleProperty("fabric_loader").get()}") {
        isTransitive = false
    }
    "platformCompileOnly"("net.neoforged.fancymodloader:loader:${providers.gradleProperty("neoforged_loader").get()}") {
        isTransitive = false
    }
    "platformCompileOnly"("net.minecraftforge:javafmllanguage:${providers.gradleProperty("forge_loader").get()}") {
        isTransitive = false
    }

    testImplementation(kotlin("test"))
    testImplementation("org.lolicode.moemusic:api:${providers.gradleProperty("plugin_api_version").get()}")
}

tasks.withType<JavaCompile>().configureEach { options.release = 17 }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        apiVersion = KotlinVersion.KOTLIN_2_2
        languageVersion = KotlinVersion.KOTLIN_2_2
    }
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

tasks.named<ProcessResources>("processPlatformResources") {
    val resourceProperties = mapOf(
        "version" to project.version,
        "mod_id" to providers.gradleProperty("mod_id").get(),
        "mod_name" to providers.gradleProperty("mod_name").get(),
        "mod_description" to providers.gradleProperty("mod_description").get(),
        "mod_author" to providers.gradleProperty("mod_author").get(),
        "mod_license" to providers.gradleProperty("mod_license").get(),
        "fabric_entrypoint" to providers.gradleProperty("fabric_entrypoint").get(),
        "moemusic_version" to providers.gradleProperty("moemusic_version").get(),
    )
    inputs.properties(resourceProperties)
    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(resourceProperties)
    }
}

val archiveProjectName = project.name
tasks.jar {
    inputs.property("projectName", archiveProjectName)
    from(sourceSets["platform"].output)
    from("LICENSE") { rename { "${it}_$archiveProjectName" } }
}

tasks.named<Jar>("sourcesJar") {
    from(sourceSets["platform"].allSource)
}
