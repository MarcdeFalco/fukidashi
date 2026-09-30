// Pipeline OCR en Kotlin pur (sans Android) : testable sur le Mac contre les
// mêmes images que les scripts Python de export/.
plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

val onnxruntimeVersion = "1.30.0"

dependencies {
    // Fourni par onnxruntime-android dans l'appli, par onnxruntime (JVM) dans les tests
    compileOnly("com.microsoft.onnxruntime:onnxruntime:$onnxruntimeVersion")

    testImplementation("com.microsoft.onnxruntime:onnxruntime:$onnxruntimeVersion")
    testImplementation(kotlin("test"))
    testImplementation("org.json:json:20250517")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("models.dir", rootProject.file("../export/out").absolutePath)
    systemProperty("repo.dir", rootProject.file("..").absolutePath)
    maxHeapSize = "2g"
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
