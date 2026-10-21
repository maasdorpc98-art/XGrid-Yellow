import java.net.URL

plugins {
    id("com.android.application")
}

android {
    namespace = "com.xgrid.yellownative"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xgrid.yellownative"
        minSdk = 28
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Actual model is downloaded to assets as part of a reproducible build.
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    implementation("com.google.mediapipe:tasks-vision:0.10.32")
    implementation("org.opencv:opencv:4.12.0")
}

val poseModel = layout.projectDirectory.file("src/main/assets/pose_landmarker_full.task")
val ensurePoseModel by tasks.registering {
    outputs.file(poseModel)
    doLast {
        val f = poseModel.asFile
        if (!f.exists() || f.length() < 1_000_000L) {
            f.parentFile.mkdirs()
            val temp = File(f.parentFile, f.name + ".part")
            val connection = URL("https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/latest/pose_landmarker_full.task").openConnection()
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.getInputStream().use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
            check(temp.length() > 1_000_000L) { "Pose model download incomplete." }
            if (f.exists()) f.delete()
            check(temp.renameTo(f)) { "Could not save pose model." }
        }
    }
}
tasks.named("preBuild") { dependsOn(ensurePoseModel) }
