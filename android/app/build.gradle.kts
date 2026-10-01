import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
}

android {
    namespace = "dev.marc.japanesehelper"
    compileSdk = 36

    defaultConfig {
        // Identifiant Play Store : définitif une fois l'appli publiée
        applicationId = "com.hanko.fukidashi"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Téléphones récents et émulateur Apple Silicon : évite d'embarquer 4 ABI d'ONNX Runtime
        ndk { abiFilters += "arm64-v8a" }
    }

    // Clé de publication (upload key) : android/keystore.properties, hors git.
    // À SAUVEGARDER : sans elle, impossible de mettre à jour l'appli sur le Play Store.
    val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        if (keystoreProps != null) {
            create("upload") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Pas de minification : Kuromoji et ONNX Runtime s'appuient sur la réflexion / JNI
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload") ?: signingConfigs.getByName("debug")
        }
    }

    // Play Store : modèles et dictionnaire dans le pack de ressources :models
    assetPacks += ":models"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // Les modèles sont copiés tels quels dans filesDir au premier lancement
    androidResources { noCompress += listOf("onnx", "txt") }

    // Les bibliothèques du NPU doivent exister en fichiers sur le disque
    packaging {
        // Métadonnées en double dans les jars de Kuromoji
        resources { excludes += listOf("META-INF/CONTRIBUTORS.md", "META-INF/LICENSE.md", "META-INF/NOTICE.md") }
        jniLibs {
            useLegacyPackaging = true
            // NPU : HTP de toutes les générations de Snapdragon (V68 = 888 … V81) ; ni GPU ni DSP
            excludes += listOf("**/libQnnGpu.so", "**/libQnnDsp*.so")
        }
    }

    // En debug (APK installé par adb), les modèles sont dans l'APK ; en release (AAB),
    // ils sont dans le pack :models
    sourceSets["debug"].assets.srcDir(layout.buildDirectory.dir("generated/models"))
}

// Modèles produits par export/export_models.py (non versionnés) : détecteur et encodeur
// (poids fp16, calcul fp32 sur CPU ou fp16 sur NPU), décodeur en int8 (CPU)
val copyModels by tasks.registering(Sync::class) {
    val out = rootProject.file("../export/out/app")
    from(out) {
        include("bubble_detector.onnx")
    }
    from(out) {
        include("encoder_kv.onnx", "decoder_step.onnx", "vocab.txt")
        into("manga_ocr")
    }
    // Dictionnaire JMdict + KANJIDIC (export/build_dictionary.py), copié au 1er lancement
    from(rootProject.file("../export/out")) {
        include("dictionary.db")
    }
    into(layout.buildDirectory.dir("generated/models/models"))
    doFirst {
        check(File(out, "encoder_kv.onnx").exists()) { "Modèles absents : lancer d'abord export/export_models.py" }
        check(rootProject.file("../export/out/dictionary.db").exists()) { "Dictionnaire absent : lancer export/build_dictionary.py" }
    }
}
tasks.named("preBuild") { dependsOn(copyModels) }

dependencies {
    implementation(project(":core"))
    // Variante avec le support du NPU Qualcomm (QNN)
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0")

    implementation(platform("androidx.compose:compose-bom:2025.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    val cameraxVersion = "1.4.2"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}

// Même contenu dans le pack :models pour l'AAB de publication
val copyModelsToPack by tasks.registering(Sync::class) {
    from(copyModels)
    into(rootProject.file("models/src/main/assets/models"))
}
tasks.matching { it.name.contains("assetPack", ignoreCase = true) || it.name == "bundleRelease" }
    .configureEach { dependsOn(copyModelsToPack) }
project(":models").tasks.matching { it.name.contains("Release") }.configureEach { dependsOn(copyModelsToPack) }
