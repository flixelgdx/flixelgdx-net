plugins {
  id("flixelgdx.java-library")
}

dependencies {
  api(libs.flixelgdx.core)
  implementation(libs.jetbrains.annotations)

  testAnnotationProcessor(project(":flixelgdx-net-processor"))
  testRuntimeOnly(libs.junit.platform.launcher)
  testImplementation(platform(libs.junit.bom))
  testImplementation(libs.junit.jupiter)
}

tasks.named<Test>("test") {
  useJUnitPlatform()
}
