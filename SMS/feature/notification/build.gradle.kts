plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.smslink.feature.notification"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":network:protocol"))
    implementation(project(":network:transport"))
    implementation(project(":feature:device"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    ksp("com.google.dagger:hilt-compiler:2.51.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core:1.15.0")
    implementation("androidx.core:core-ktx:1.15.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
