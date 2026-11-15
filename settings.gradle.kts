import java.util.Properties

/**
 * Root settings for the FlixelGDX Net build.
 *
 * <p>Declares the build-logic included build so convention plugins are available to every module,
 * and centralizes repository declarations, including where the published FlixelGDX framework
 * artifact (`org.flixelgdx:flixelgdx-core`) is resolved from.
 */

pluginManagement {
  includeBuild("build-logic")
  repositories {
    gradlePluginPortal()
    google()
    mavenCentral()
  }
}

plugins {
  id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

// The Android module is optional so the extension can be built without an Android SDK.
// Enable via: -PincludeAndroid=true (CI / one-off) or includeAndroid=true in local.properties (gitignored).
val includeAndroidFromCli = startParameter.projectProperties["includeAndroid"] == "true"
val includeAndroidFromLocal = run {
  val f = File(settingsDir, "local.properties")
  if (f.exists()) {
    val props = Properties()
    f.inputStream().use(props::load)
    props.getProperty("includeAndroid", "false") == "true"
  } else {
    false
  }
}
val includeAndroid = includeAndroidFromCli || includeAndroidFromLocal
gradle.extra["includeAndroid"] = includeAndroid

dependencyResolutionManagement {
  repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
  repositories {
    mavenCentral()
    gradlePluginPortal()
    google()
  }
}

rootProject.name = "flixelgdx-net"

include("flixelgdx-net-core")
include("flixelgdx-net-processor")
include("flixelgdx-net-jvm")
include("flixelgdx-net-html5")

// One jar per desktop platform holding the native WebRTC library. The library of a platform is
// never committed, so a fresh checkout has no folder for it, and Gradle refuses a project without
// one; the folder is created here.
val nativeTargets = listOf(
  "linux-x86_64", "linux-aarch64", "windows-x86_64", "windows-aarch64", "macos-x86_64", "macos-aarch64"
)
for (target in nativeTargets) {
  include("flixelgdx-net-natives-$target")
  project(":flixelgdx-net-natives-$target").projectDir = file("flixelgdx-net-natives/$target").also { it.mkdirs() }
}

if (includeAndroid) {
  include("flixelgdx-net-android")
}
