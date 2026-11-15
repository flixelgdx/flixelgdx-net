plugins {
  id("flixelgdx.java-library")
}

// Runs a game with no window, for dedicated servers. JVM only: it needs threads and a real clock.
dependencies {
  api(project(":flixelgdx-net-jvm"))
  implementation(libs.jetbrains.annotations)

  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}
