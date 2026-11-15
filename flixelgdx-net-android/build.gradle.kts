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

dependencies {
  api(project(":flixelgdx-net-jvm"))
  implementation(libs.jetbrains.annotations)
}
