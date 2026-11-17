plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Signing: when CI (or you, locally) provides a keystore through the environment, both
// the release and debug APKs are signed with that key so every build installs cleanly
// over the previous one. Supports both ComicReader secrets (KEYSTORE_FILE, KEYSTORE_PASSWORD,
// KEY_ALIAS, KEY_PASSWORD) and QA workflow secrets ($HOME/work/_temp/keystore/key.jks,
// SIGNING_STORE_PASSWORD, SIGNING_KEY_ALIAS, SIGNING_KEY_PASSWORD).
val defaultKeystorePath = System.getProperty("user.home")?.let { "$it/work/_temp/keystore/key.jks" }
val stableKeystore: File? = (System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotBlank() } ?: defaultKeystorePath)
    ?.let { File(it) }
    ?.takeIf { it.isFile && it.length() > 0L }
val storePwd: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: System.getenv("SIGNING_STORE_PASSWORD")?.takeIf { it.isNotBlank() }
val aliasName: String? = System.getenv("KEY_ALIAS")?.takeIf { it.isNotBlank() }
    ?: System.getenv("SIGNING_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val keyPwd: String? = System.getenv("KEY_PASSWORD")?.takeIf { it.isNotBlank() }
    ?: System.getenv("SIGNING_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

val hasStableCredentials = stableKeystore != null &&
    !storePwd.isNullOrBlank() &&
    !aliasName.isNullOrBlank() &&
    !keyPwd.isNullOrBlank()

android {
    namespace = "com.infinity.comicreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.infinity.comicreader"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "2.4"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasStableCredentials) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = storePwd
                keyAlias = aliasName
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
        release {
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module"
        )
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Archive support
    implementation("com.github.junrar:junrar:8.1.2")          // RAR / CBR (RAR4 + RAR5/7)
    implementation("org.apache.commons:commons-compress:1.26.2") // 7z / CB7
    implementation("commons-io:commons-io:2.16.1")
    implementation("org.tukaani:xz:1.9")
    implementation("org.slf4j:slf4j-nop:2.0.17")              // silence junrar/slf4j on Android
    // PDF uses the platform android.graphics.pdf.PdfRenderer (no dependency)
}
