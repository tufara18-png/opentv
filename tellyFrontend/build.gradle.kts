plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val filteredTellySources = layout.buildDirectory.dir("generated/tellySources")

val syncTellySources by tasks.registering(Sync::class) {
    from(rootProject.file("telly/app/src/main/java")) {
        exclude("com/johncorser/telly/features/playback/TuneController.kt")
    }
    into(filteredTellySources)
}

android {
    namespace = "com.johncorser.telly"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        targetSdk = 36
    }

    sourceSets {
        getByName("main") {
            java.srcDir(filteredTellySources)
            res.srcDir("../telly/app/src/main/res")
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
        buildConfig = true
    }
}

tasks.matching {
    it.name.startsWith("ksp") || it.name.startsWith("compile")
}.configureEach {
    dependsOn(syncTellySources)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.xz)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}
