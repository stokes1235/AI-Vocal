import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    alias(libs.plugins.android.application)
}

// Supply these through the local build environment or CI secret settings.
val releaseStorePassword = providers.environmentVariable("AI_VOCAL_STORE_PASSWORD")
val releaseKeyPassword = providers.environmentVariable("AI_VOCAL_KEY_PASSWORD")

android {
    signingConfigs {
        create("release") {
            storeFile = file("D:\\MyApplication1\\my_pw")
            storePassword = releaseStorePassword.orNull
            keyAlias = "framework"
            keyPassword = releaseKeyPassword.orNull
        }
    }
    namespace = "com.stokes.aivocal"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.stokes.aivocal"
        minSdk = 32
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    packaging {
        jniLibs {
            pickFirsts += "**/libc++_shared.so"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// Keep release credentials optional for sync/debug, but required before release work.
val validateReleaseSigningPasswords = tasks.register("validateReleaseSigningPasswords") {
    doLast {
        if (releaseStorePassword.orNull.isNullOrBlank() ||
            releaseKeyPassword.orNull.isNullOrBlank()
        ) {
            throw GradleException(
                "Release signing requires AI_VOCAL_STORE_PASSWORD and AI_VOCAL_KEY_PASSWORD."
            )
        }
    }
}

tasks.matching {
    it.name == "preReleaseBuild" || it.name == "validateSigningRelease"
}.configureEach {
    dependsOn(validateReleaseSigningPasswords)
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// text-recognition 的传递依赖曾指向不存在的 19.1.0；Google Maven 上该构件最新为 19.0.1。
configurations.configureEach {
    resolutionStrategy {
        force("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.sherpa.onnx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}