plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)

    id("com.google.gms.google-services")
}

android {
    namespace = "com.ooplab.exercises_fitfuel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ooplab.exercises_fitfuel"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

    //firebase 관련
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore-ktx")

    implementation("com.google.firebase:firebase-storage-ktx")

    // Kotlin 코루틴
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation ("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ExoPlayer core (기본 플레이어 기능), ExoPlayer UI (PlayerView 포함)
    implementation ("androidx.media3:media3-exoplayer:1.3.0")
    implementation ("androidx.media3:media3-ui:1.3.0")

    // Glide (이미지 로딩 라이브러리)
    implementation ("com.github.bumptech.glide:glide:4.16.0")
    annotationProcessor ("com.github.bumptech.glide:compiler:4.16.0")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.0")
    implementation("androidx.camera:camera-core:1.3.4")
}