/**
 * Root aggregator for the FlixelGDX Net build.
 *
 * <p>The root project holds no source itself; it only applies IDE plugins and sets the shared
 * version. All module setup lives in the convention plugins under build-logic/src/main/kotlin/.
 */

plugins {
  eclipse
  idea
  // These plugins must be declared in the root classpath scope to prevent classloader conflicts
  // when convention plugins apply them to the modules. Neither is applied to the root project
  // itself; the modules apply them via the flixelgdx.* convention plugins.
  alias(libs.plugins.spotless) apply false
  alias(libs.plugins.vanniktech) apply false
}

// The root project deliberately has no group, so a game that includes this build as a composite
// build only ever sees the org.flixelgdx:flixelgdx-net-* modules. Each module gets its group from
// the convention plugins.
version = gitVersion()

eclipse.project.name = "flixelgdx-net-parent"

fun gitVersion(): String = try {
  val proc = ProcessBuilder("git", "describe", "--tags", "--abbrev=0")
    .directory(rootDir)
    .start()
  proc.waitFor()
  proc.inputStream.bufferedReader().readText().trim().removePrefix("v").ifEmpty { "unspecified" }
} catch (_: Exception) {
  "unspecified"
}

idea {
  module {
    outputDir = file("build/classes/java/main")
    testOutputDir = file("build/classes/java/test")
  }
}
