import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "me.rerere.rikkahub.terminalbenchmark.test"
    compileSdk = 36
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = false // Installation-only test signature; no debuggable APK in the V5 baseline.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    targetProjectPath = ":benchmarks:terminal-target"
    sourceSets.getByName("main").kotlin.srcDir("../production-contract/src/main/kotlin")
    experimentalProperties["android.experimental.self-instrumenting"] = true
}
androidComponents {
    beforeVariants { it.enable = it.buildType == "benchmark" }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
