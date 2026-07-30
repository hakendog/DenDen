plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

val releaseStoreFilePath = providers.gradleProperty("DENDEN_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("DENDEN_RELEASE_STORE_FILE")).orNull
val releaseStorePassword = providers.gradleProperty("DENDEN_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("DENDEN_RELEASE_STORE_PASSWORD")).orNull
val releaseKeyAlias = providers.gradleProperty("DENDEN_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("DENDEN_RELEASE_KEY_ALIAS")).orNull
val releaseKeyPassword = providers.gradleProperty("DENDEN_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("DENDEN_RELEASE_KEY_PASSWORD")).orNull
val releaseSigningConfigured = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.tensal.denden"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tensal.denden"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFilePath))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

val verifyReleaseSigning by tasks.registering {
    group = "verification"
    description = "拒絕未設定正式簽章或誤用 Android debug keystore 的 release 成品"
    doLast {
        if (!releaseSigningConfigured) {
            throw GradleException(
                "release 簽章尚未設定；請由維護者在 repository 外提供 DENDEN_RELEASE_STORE_FILE、" +
                    "DENDEN_RELEASE_STORE_PASSWORD、DENDEN_RELEASE_KEY_ALIAS、DENDEN_RELEASE_KEY_PASSWORD"
            )
        }
        val store = file(requireNotNull(releaseStoreFilePath))
        if (!store.isFile) throw GradleException("release keystore 不存在：${store.absolutePath}")
        val canonicalStore = store.canonicalFile
        val normalized = canonicalStore.path.replace('\\', '/').lowercase()
        if (store.name.equals("debug.keystore", ignoreCase = true) || normalized.endsWith("/.android/debug.keystore")) {
            throw GradleException("release 禁止使用 Android debug keystore")
        }
        if (canonicalStore.toPath().startsWith(rootProject.projectDir.canonicalFile.toPath())) {
            throw GradleException("release keystore 必須位於 repository 外")
        }
    }
}

tasks.configureEach {
    if (name in setOf("packageRelease", "assembleRelease", "bundleRelease")) dependsOn(verifyReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20260522")

    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    implementation(platform("com.google.firebase:firebase-bom:34.5.0"))
    implementation("com.google.firebase:firebase-messaging")
    implementation("com.google.firebase:firebase-installations")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("com.joaomgcd:taskerpluginlibrary:0.4.10")
    implementation("com.revenuecat.purchases:slide-to-unlock:1.0.2")

    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("Play Services otherwise resolves obsolete Fragment 1.1.0")
        }
    }
}
