plugins { id("com.android.library") }

android {
    namespace = "androidx.media3.decoder.ffmpeg"
    compileSdk = 35
    defaultConfig { minSdk = 21 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-decoder:1.5.1")
    implementation("org.checkerframework:checker-qual:3.33.0")
    implementation("androidx.annotation:annotation-jvm:1.9.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
}
