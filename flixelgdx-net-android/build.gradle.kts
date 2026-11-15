// Android packaging for the FlixelGDX net extension. Only included when the Android SDK is
// available (see includeAndroid in settings.gradle.kts).
//
// The transports themselves live in flixelgdx-net-jvm. This module adds what an Android app
// needs on top of them: the native WebRTC library for each Android CPU type (under
// src/main/jniLibs, built by the Build Natives workflow) and the permission to use the network.

plugins {
  id("flixelgdx.android-library")
}

android {
  namespace = "org.flixelgdx.net.android"
  compileSdk = 36

  defaultConfig {
    minSdk = 26
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
  }
}

// The native libraries come with a licenses.txt that lists the third-party software inside them.
// It sits next to each library, where the Android plugin ignores it, so it is copied into the
// library's Java resources to travel with the published artifact.
val nativeLicenses = tasks.register<Copy>("nativeLicenses") {
  from("src/main/jniLibs/arm64-v8a") {
    include("licenses.txt")
  }
  into(layout.buildDirectory.dir("generated/nativeLicenses/META-INF/flixelgdx-net"))
}

android.sourceSets.getByName("main").resources.srcDir(layout.buildDirectory.dir("generated/nativeLicenses"))

tasks.matching { it.name == "preBuild" }.configureEach {
  dependsOn(nativeLicenses)
}

dependencies {
  api(project(":flixelgdx-net-jvm"))
  implementation(libs.jetbrains.annotations)
}
