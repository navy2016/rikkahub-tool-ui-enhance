import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Copy the real gesture/controller implementation. This fixture must never fork its behavior.
abstract class SyncViewportSources @Inject constructor(
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

val syncViewportSources by tasks.registering(SyncViewportSources::class) {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("app/src/main/java"))
    includedPaths.set(listOf(
        "me/rerere/rikkahub/utils/TerminalEmulator.kt",
        "me/rerere/rikkahub/data/container/TerminalViewportReducer.kt",
        "me/rerere/rikkahub/data/container/TerminalViewportController.kt",
        "me/rerere/rikkahub/data/container/TerminalViewportGeometry.kt",
        "me/rerere/rikkahub/data/container/TerminalViewportItemGeometry.kt",
        "me/rerere/rikkahub/data/container/TerminalLazyViewportAdapter.kt",
        "me/rerere/rikkahub/data/container/TerminalViewportState.kt",
        "me/rerere/rikkahub/data/container/TerminalFastFlingTracker.kt",
        "me/rerere/rikkahub/ui/pages/container/TerminalViewportGestures.kt",
        "me/rerere/rikkahub/ui/pages/container/TerminalViewportScrollEffects.kt",
        "me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt",
        "me/rerere/rikkahub/ui/theme/Type.kt",
    ))
    outputDirectory.set(layout.buildDirectory.dir("generated/viewportSources"))
}
val syncViewportResources by tasks.registering(SyncViewportSources::class) {
    sourceDirectory.set(rootProject.layout.projectDirectory.dir("app/src/main/res"))
    includedPaths.set(listOf("font/jetbrains_mono.ttf"))
    outputDirectory.set(layout.buildDirectory.dir("generated/viewportResources"))
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk = 36
    defaultConfig {
        applicationId = "me.rerere.rikkahub.terminalviewporttest"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}
androidComponents {
    beforeVariants { it.enable = it.buildType == "debug" }
    onVariants { variant ->
        variant.sources.java?.addGeneratedSourceDirectory(syncViewportSources) { it.outputDirectory }
        variant.sources.res?.addGeneratedSourceDirectory(syncViewportResources) { it.outputDirectory }
    }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation("androidx.compose.foundation:foundation")
    debugImplementation(libs.androidx.ui.test.manifest)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
