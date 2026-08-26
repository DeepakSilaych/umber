import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    // Only exercised by app/src/cloud (kotlinx.serialization @Serializable wire DTOs). Applying the
    // compiler plugin project-wide costs nothing at runtime for the privacy flavour — it's the
    // `cloudImplementation` dependency below that would actually pull a jar in, and privacy gets
    // none of those.
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

/**
 * Signing credentials come from `local.properties`, which is gitignored and never leaves this
 * machine. Nothing secret belongs in this file — it is committed.
 *
 * A missing keystore is not an error: `assembleDebug` and the test suite must keep working for
 * anyone who clones the repo without one. Only `assembleRelease` needs it.
 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) FileInputStream(file).use { load(it) }
}

val releaseKeystore: File? = localProps.getProperty("umber.keystore")
    ?.let { File(it.replace("~", System.getProperty("user.home"))) }
    ?.takeIf { it.exists() }

android {
    namespace = "com.deepak.umber"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.deepak.umber"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "0.1.3"
    }

    /**
     * Two builds from one source tree.
     *
     * `privacy` is the product as described: no `INTERNET` permission, so the guarantee is enforced
     * by the OS rather than promised in a policy. It stays the default.
     *
     * `cloud` trades that for remote classification and sync. It gets a different application id
     * and display name deliberately — installed side by side they must be distinguishable, and the
     * build that gives something up should be the one that has to explain itself.
     */
    flavorDimensions += "privacy"

    productFlavors {
        create("privacy") {
            dimension = "privacy"
            isDefault = true
            buildConfigField("boolean", "CLOUD_ENABLED", "false")
        }
        create("cloud") {
            dimension = "privacy"
            applicationIdSuffix = ".cloud"
            versionNameSuffix = "-cloud"
            buildConfigField("boolean", "CLOUD_ENABLED", "true")
            // Overridable at runtime in a debug build (see Settings' debug-only endpoint field) so
            // this can be pointed at a local dev server instead.
            buildConfigField("String", "SYNC_BASE_URL", "\"https://finance.deepaksilaych.me\"")
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = localProps.getProperty("umber.storePassword")
                keyAlias = localProps.getProperty("umber.keyAlias") ?: "umber"
                keyPassword = localProps.getProperty("umber.keyPassword")
                    ?: localProps.getProperty("umber.storePassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Distinct id so a debug build can sit alongside an installed release without
            // either one overwriting the other's data.
            applicationIdSuffix = ".debug"
        }
        release {
            // Unminified deliberately: this is a sideload build, and a readable stack trace from a
            // bug report is worth more than a few megabytes. R8 with Room + Compose + Glance also
            // needs keep rules that aren't worth the risk of a silently broken release.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        // Only so Settings can show the running version. Without it there is no way for the app to
        // tell the user which build they have, which makes bug reports guesswork.
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // The cloud flavour's sync transport. Scoped with `cloudImplementation` so none of it — jars,
    // transitive deps, anything — lands in the privacy flavour's build, per README's "Two builds".
    "cloudImplementation"(libs.retrofit)
    "cloudImplementation"(libs.retrofit.kotlinx.serialization.converter)
    "cloudImplementation"(libs.okhttp)
    "cloudImplementation"(libs.kotlinx.serialization.json)
    "cloudImplementation"(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
