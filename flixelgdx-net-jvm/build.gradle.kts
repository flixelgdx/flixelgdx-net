plugins {
  id("flixelgdx.java-library")
}

// Transports for every JVM platform (desktop, Android, and dedicated servers). This module must
// stay inside the Java APIs that Android has, and it must never be a dependency of the web
// module, which has no sockets or threads.
dependencies {
  api(project(":flixelgdx-net-core"))
  implementation(libs.jetbrains.annotations)

  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
  testAnnotationProcessor(project(":flixelgdx-net-processor"))
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}
