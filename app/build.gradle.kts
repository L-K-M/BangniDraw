plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val mixboxEnabled = providers.gradleProperty("bangnidraw.mixbox")
    .map(String::toBooleanStrict)
    .getOrElse(true)

android {
    namespace = "ch.lkmc.bangnidraw"
    compileSdkVersion("android-37.0")

    defaultConfig {
        // Never changes: an applicationId change breaks upgrades for every
        // sideloaded install (PLAN.md "Renaming").
        applicationId = "ch.lkmc.bangnidraw"
        // 29: permission-free MediaStore writes, front-buffered rendering,
        // SurfaceControl — the drawing stack's floor (ADR 0002).
        minSdk = 29
        targetSdk = 37
        versionCode = 9
        versionName = "1.0.7"
        buildConfigField("boolean", "MIXBOX", mixboxEnabled.toString())
        resValue("bool", "mixbox_enabled", mixboxEnabled.toString())
    }

    signingConfigs {
        // A checked-in debug keystore signs BOTH build types: zero-secret CI,
        // reproducible builds, anyone can build an upgrade-compatible APK.
        // Deliberate sideload-only decision — see docs/decisions/0005.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(if (mixboxEnabled) "src/mixbox/java" else "src/nomixbox/java")
            if (mixboxEnabled) assets.srcDir("src/mixbox/assets")
        }
        if (mixboxEnabled) {
            getByName("test").kotlin.srcDir("src/testMixbox/java")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.window.size)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // The drawing stack — pulled in from the scaffold on so CI proves
    // resolution before roadmap step 2 depends on them.
    implementation(libs.androidx.graphics.core)
    implementation(libs.androidx.input.motionprediction)
    // CC BY-NC 4.0 — ADR 0003. The app is non-commercial as distributed.
    if (mixboxEnabled) implementation(libs.mixbox)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

tasks.withType<Test>().configureEach {
    // The golden-stroke test regenerates its pinned file when this is set
    // (`docs/plan/11-testing.md` §6). Gradle does not forward the launching
    // JVM's system properties to the test JVM, so it has to be passed through
    // explicitly or `-Dbangni.updateGolden=true` would silently do nothing.
    systemProperty(
        "bangni.updateGolden",
        providers.gradleProperty("bangni.updateGolden")
            .orElse(providers.systemProperty("bangni.updateGolden"))
            .getOrElse("false"),
    )
}
