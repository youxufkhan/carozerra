plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.youxufkhan.carozerra"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.youxufkhan.carozerra"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.3.0-beta.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
}

val syncSharedAssets by tasks.registering(Sync::class) {
    // Only the two things the app needs — assets/readme/ stays out of the APK.
    from(rootProject.file("../assets/clips")) { into("clips") }
    from(rootProject.file("../assets/pioneer.png"))
    into(layout.buildDirectory.dir("generated/sharedAssets"))
}

android.sourceSets.getByName("main").assets.srcDir(
    layout.buildDirectory.dir("generated/sharedAssets")
)

tasks.named("preBuild") { dependsOn(syncSharedAssets) }
