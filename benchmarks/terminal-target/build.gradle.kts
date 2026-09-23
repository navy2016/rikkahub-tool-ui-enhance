import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Compile the real production renderer, not a benchmark-only reimplementation. Generated copies
// live only in build/ so changes to the production files automatically invalidate this task.
abstract class SyncTerminalFiles @Inject constructor(
    private val fileOperations: FileSystemOperations,
) : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:Input
    abstract val includedPaths: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun sync() {
        fileOperations.sync {
            from(sourceDirectory) { include(includedPaths.get()) }
            into(outputDirectory)
        }
    }
}

val syncTerminalSources by tasks.registering(SyncTerminalFiles::class) {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("app/src/main/java"))
    includedPaths.set(listOf(
        "me/rerere/rikkahub/utils/TerminalEmulator.kt",
        "me/rerere/rikkahub/data/container/TerminalViewportReducer.kt",
        "me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt",
        "me/rerere/rikkahub/ui/theme/Type.kt",
    ))
    outputDirectory.set(layout.buildDirectory.dir("generated/terminalSources"))
}
val syncTerminalResources by tasks.registering(SyncTerminalFiles::class) {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("app/src/main/res"))
    includedPaths.set(listOf("font/jetbrains_mono.ttf"))
    outputDirectory.set(layout.buildDirectory.dir("generated/terminalResources"))
}

android {
    // The shared Type.kt refers to the production R namespace. The installable app ID is separate.
    namespace = "me.rerere.rikkahub"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.rerere.rikkahub.terminalbenchmark"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isProfileable = true
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(syncTerminalSources) { it.outputDirectory }
        variant.sources.res?.addGeneratedSourceDirectory(syncTerminalResources) { it.outputDirectory }
    }
}
composeCompiler {
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("app/compose_compiler_config.conf"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation("androidx.compose.foundation:foundation")
    testImplementation(libs.junit)
}
