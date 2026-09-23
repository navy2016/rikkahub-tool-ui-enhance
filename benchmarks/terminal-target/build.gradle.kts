plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Compile the real production renderer, not a benchmark-only reimplementation. Generated copies
// live only in build/ so changes to the production files automatically invalidate this task.
val terminalSources = layout.buildDirectory.dir("generated/terminalSources")
val terminalResources = layout.buildDirectory.dir("generated/terminalResources")
val syncTerminalSources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/java")) {
        include("me/rerere/rikkahub/utils/TerminalEmulator.kt")
        include("me/rerere/rikkahub/data/container/TerminalViewportReducer.kt")
        include("me/rerere/rikkahub/ui/pages/container/TerminalRenderedRows.kt")
        include("me/rerere/rikkahub/ui/theme/Type.kt")
    }
    into(terminalSources)
}
val syncTerminalResources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/res")) { include("font/jetbrains_mono.ttf") }
    into(terminalResources)
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
    sourceSets.getByName("main") {
        java.srcDir(terminalSources)
        res.srcDir(terminalResources)
    }
}
androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
}
tasks.named("preBuild") { dependsOn(syncTerminalSources, syncTerminalResources) }
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
