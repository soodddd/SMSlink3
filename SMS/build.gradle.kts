plugins {
    id("com.android.application") version "8.6.1" apply false
    id("com.android.library") version "8.6.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.25" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "1.9.25-1.0.20" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
