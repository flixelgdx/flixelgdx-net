plugins {
  id("flixelgdx.java-library")
}

// Rollback netcode. It only needs the core module, so it works on every platform, the web
// included.
dependencies {
  api(project(":flixelgdx-net-core"))
  implementation(libs.jetbrains.annotations)

  testAnnotationProcessor(project(":flixelgdx-net-processor"))
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}
