// Web transports for the FlixelGDX net extension. The TeaVM plugin is applied so the browser
// interop (org.teavm.jso) is on the compile classpath, the same way the framework's own web
// backend gets it.

plugins {
  id("flixelgdx.java-library")
  alias(libs.plugins.teavm)
}

dependencies {
  api(project(":flixelgdx-net-core"))
  implementation(libs.jetbrains.annotations)
}
