plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.readle.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.readle.app"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionCode=<run number> and -PversionName=<tag>; local builds default to 1 / 1.0.
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Ship only English strings from libraries (Readle's own UI is English).
        resourceConfigurations += listOf("en")
    }

    // Release signing comes from environment variables (set by CI from GitHub secrets, or exported locally).
    // Without them the release build is still produced, just unsigned.
    val keystorePath: String? = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null && File(keystorePath).exists()) {
            create("release") {
                storeFile = File(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
                enableV1Signing = false // minSdk 26 verifies APK Signature Scheme v2/v3, so v1 only adds size
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true      // R8: shrink + optimize + obfuscate code
            isShrinkResources = true    // drop unused resources
            isDebuggable = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Signing/Play metadata blobs that add size and no value for a sideloaded or Play-delivered app.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // AAB: Play delivers only the language/density/ABI splits a device needs.
    bundle {
        language { enableSplit = true }
        density { enableSplit = true }
        abi { enableSplit = true }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // Drop Kotlin's runtime null-check intrinsics; fewer instructions and strings in the dex.
        freeCompilerArgs += listOf("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*",
            "/META-INF/DEPENDENCIES",
            "/META-INF/*.version",
            "/META-INF/proguard/**",
            "/META-INF/com.android.tools/**",
            "/kotlin/**",
            "/kotlinx/**",
            "DebugProbesKt.bin",
            "kotlin-tooling-metadata.json",
            "**/*.kotlin_builtins",
            "**/*.kotlin_metadata"
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
