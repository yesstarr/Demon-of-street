plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
<<<<<<< HEAD
=======
    id("com.google.gms.google-services")
>>>>>>> 181b93c0db215785ed82cca6d26ee425bba7c77a
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
<<<<<<< HEAD

=======
        renderscriptTargetApi = 21
        renderscriptSupportModeEnabled = true
>>>>>>> 181b93c0db215785ed82cca6d26ee425bba7c77a
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
<<<<<<< HEAD
=======

    buildFeatures {
        viewBinding = true
    }

>>>>>>> 181b93c0db215785ed82cca6d26ee425bba7c77a
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {

<<<<<<< HEAD
=======
    //firebase 관련
    implementation(platform("com.google.firebase:firebase-bom:33.13.0"))
    implementation("com.google.firebase:firebase-analytics")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore-ktx")

>>>>>>> 181b93c0db215785ed82cca6d26ee425bba7c77a
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
<<<<<<< HEAD

=======
>>>>>>> 181b93c0db215785ed82cca6d26ee425bba7c77a
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("com.google.mediapipe:tasks-vision:0.10.0")
    implementation("androidx.camera:camera-core:1.3.4")
}