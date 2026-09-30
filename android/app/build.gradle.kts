plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "dev.marc.japanesehelper"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.marc.japanesehelper"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
        // Téléphones récents et émulateur Apple Silicon : évite d'embarquer 4 ABI d'ONNX Runtime
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Les modèles sont copiés tels quels dans filesDir au premier lancement
    androidResources { noCompress += listOf("onnx", "txt") }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/models"))
}

// Modèles produits par export/export_models.py (non versionnés)
val copyModels by tasks.registering(Copy::class) {
    val out = rootProject.file("../export/out")
    from(out) {
        include("bubble_detector_int8.onnx")
        rename { "bubble_detector.onnx" }
    }
    from(File(out, "manga_ocr_int8")) {
        include("encoder_model.onnx", "decoder_model.onnx", "vocab.txt")
        into("manga_ocr")
    }
    into(layout.buildDirectory.dir("generated/models/models"))
    doFirst {
        check(File(out, "bubble_detector_int8.onnx").exists()) {
            "Modèles absents : lancer d'abord export/export_models.py"
        }
    }
}
tasks.named("preBuild") { dependsOn(copyModels) }

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
}
