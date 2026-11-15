import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven

/**
 * Convention for the flixelgdx-net-natives-<target> modules.
 *
 * <p>Each one is a jar that holds nothing but the native WebRTC library of one desktop platform
 * at natives/<target>/<library name>, plus the licenses of what is inside it, so a game only
 * bundles the platforms it ships to. The library is never committed: the "Build Natives"
 * workflow builds it, and its artifact is unpacked into a checkout before publishing.
 */

plugins {
  id("flixelgdx.java-library")
}

val target = project.name.removePrefix("flixelgdx-net-natives-")
val libraryName = when {
  target.startsWith("windows") -> "flixelnetrtc.dll"
  target.startsWith("macos") -> "libflixelnetrtc.dylib"
  else -> "libflixelnetrtc.so"
}
val library = layout.projectDirectory.file("src/main/resources/natives/$target/$libraryName").asFile

description = "The native WebRTC library of FlixelGDX Net for $target."

// A natives jar without its library is useless, so building one only warns (the other modules
// do not need it), while publishing one fails so that a release can never ship an empty jar.
tasks.named("processResources") {
  doFirst {
    if (!library.isFile) {
      logger.quiet(
        "WARNING: ${project.name} is empty because ${library.relativeTo(rootProject.projectDir)} does not exist. " +
          "Download the rtc-natives-all artifact of the Build Natives workflow and unpack it into the checkout."
      )
    }
  }
}

tasks.withType<AbstractPublishToMaven>().configureEach {
  doFirst {
    if (!library.isFile) {
      throw GradleException(
        "Refusing to publish ${project.name}: ${library.relativeTo(rootProject.projectDir)} does not exist. " +
          "Download the rtc-natives-all artifact of the Build Natives workflow first."
      )
    }
  }
}
