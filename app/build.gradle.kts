import org.gradle.api.tasks.PathSensitivity
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
        versionCode = 18
        versionName = "1.2.2"
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
            // The engine-gl variant code and the licensed assets moved to
            // :engine-gl (DESKTOP.md M2); the app still packages the assets
            // from their single copy there. Gradle tolerates a missing
            // srcDir silently, so guard it: a relocated directory must fail
            // the build, not the GL runtime.
            if (mixboxEnabled) {
                val mixboxAssets = file("../engine-gl/src/mixbox/assets")
                require(mixboxAssets.isDirectory) {
                    ":engine-gl mixbox assets not found at $mixboxAssets"
                }
                assets.srcDir(mixboxAssets)
            }
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

    // Pure-JVM engine model layer (DESKTOP.md Phase 1, M1).
    implementation(project(":engine-core"))

    // The drawing stack — pulled in from the scaffold on so CI proves
    // resolution before roadmap step 2 depends on them.
    implementation(libs.androidx.graphics.core)
    implementation(libs.androidx.input.motionprediction)

    // The GLES 3.0 engine (DESKTOP.md M2): gl code, the GLES30 facade, and
    // the mixbox/nomixbox variant now live in :engine-gl. The Mixbox java
    // library dependency moved there with them.
    implementation(project(":engine-gl"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test)
}

/** Repo files a unit test pins that no compilation would otherwise track. */
val WORKFLOW_CONTRACT_FILES = listOf(
    ".github/workflows/ci.yml",
    ".github/workflows/release.yml",
)

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

    // ReleaseBuildCoverageContractTest reads the CI workflows, which are not
    // otherwise inputs to anything this module builds. Without declaring
    // them, editing a workflow leaves the test task UP-TO-DATE and the pin
    // silently does not run — precisely on the change it exists to catch.
    // Every other contract test reads sources whose compilation already
    // forces the re-run.
    inputs
        .files(rootProject.layout.projectDirectory.files(WORKFLOW_CONTRACT_FILES))
        .withPropertyName("workflowContracts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}
