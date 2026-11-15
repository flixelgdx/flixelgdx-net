# Builds the one native library the JVM side of the net extension loads: a small JNI layer over
# libdatachannel, with libdatachannel and Mbed TLS linked in statically so nothing else has to be
# installed on a player's computer.
#
# FLIXELNET_DEPS must point at a folder holding the sources of "libdatachannel" (with its
# submodules) and "mbedtls". scripts/build_natives.sh fetches them and calls this file.

cmake_minimum_required(VERSION 3.20)

# Lets CMAKE_MSVC_RUNTIME_LIBRARY reach the bundled projects, so Windows builds need no extra
# runtime installed.
set(CMAKE_POLICY_DEFAULT_CMP0091 NEW)
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded")

project(flixelnetrtc C CXX)

if(NOT FLIXELNET_DEPS)
  message(FATAL_ERROR "Set FLIXELNET_DEPS to the folder holding libdatachannel and mbedtls.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(BUILD_SHARED_LIBS OFF)
# Nothing here is ever installed; the one library that matters is copied out by the build script.
set(CMAKE_SKIP_INSTALL_RULES ON)

# Mbed TLS does the encryption. Only its static libraries are wanted.
set(ENABLE_PROGRAMS OFF)
set(ENABLE_TESTING OFF)
set(GEN_FILES OFF)
set(MBEDTLS_FATAL_WARNINGS OFF)
set(USE_STATIC_MBEDTLS_LIBRARY ON)
set(USE_SHARED_MBEDTLS_LIBRARY OFF)
set(MBEDTLS_USER_CONFIG_FILE "${CMAKE_CURRENT_SOURCE_DIR}/mbedtls_user_config.h" CACHE FILEPATH "" FORCE)
add_subdirectory("${FLIXELNET_DEPS}/mbedtls" mbedtls EXCLUDE_FROM_ALL)
# libdatachannel looks for the target under this name. It is declared as an imported target so
# that libdatachannel's install rules, which are never used here, do not object to it.
add_library(MbedTLS::MbedTLS INTERFACE IMPORTED GLOBAL)
set_target_properties(MbedTLS::MbedTLS PROPERTIES INTERFACE_LINK_LIBRARIES mbedtls)

# libdatachannel does WebRTC. Data channels are all that is needed, so media and its own WebSocket
# support are left out, which keeps the library small.
set(USE_MBEDTLS ON)
set(NO_WEBSOCKET ON)
set(NO_MEDIA ON)
set(NO_EXAMPLES ON)
set(NO_TESTS ON)
add_subdirectory("${FLIXELNET_DEPS}/libdatachannel" libdatachannel EXCLUDE_FROM_ALL)

add_library(flixelnetrtc SHARED flixelnet_rtc.cpp)
target_link_libraries(flixelnetrtc PRIVATE datachannel-static)

if(ANDROID)
  # The Android toolchain ships the JNI header and the log library.
  target_link_libraries(flixelnetrtc PRIVATE log)
else()
  find_package(JNI REQUIRED)
  target_include_directories(flixelnetrtc PRIVATE ${JNI_INCLUDE_DIRS})
endif()

if(CMAKE_SYSTEM_NAME STREQUAL "Linux")
  # Carry the C++ runtime inside the library and keep the bundled projects' symbols private, so
  # the library loads on any distribution and cannot clash with other native code in the game.
  target_link_options(flixelnetrtc PRIVATE -static-libstdc++ -static-libgcc -Wl,--exclude-libs,ALL -s)
elseif(ANDROID)
  target_link_options(flixelnetrtc PRIVATE -Wl,--exclude-libs,ALL -s)
endif()
