cmake_minimum_required(VERSION 3.22)
project(rapidyenc_android C CXX)

set(RAPIDYENC_SRC_DIR "" CACHE PATH "Path to the rapidyenc source checkout")
if(NOT EXISTS "${RAPIDYENC_SRC_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "RAPIDYENC_SRC_DIR does not point at a rapidyenc checkout: ${RAPIDYENC_SRC_DIR}")
endif()

# Upstream CMakeLists is used untouched. EXCLUDE_FROM_ALL keeps its CLI and bench
# executables out of the build; Gradle requests the rapidyenc_shared target only.
add_subdirectory(${RAPIDYENC_SRC_DIR} rapidyenc EXCLUDE_FROM_ALL)

# rapidyenc asks the processor for NEON, CRC32 and PMULL at run time, in platform.cc and
# crc.cc. On Android those files include only the NDK's cpu-features.h and then test
# defined(ANDROID_CPU_FAMILY_ARM), which is an enum in that header, not a macro, so neither
# the cpufeatures branch nor the getauxval one was compiled: 32-bit ARM fell back to the
# generic kernels for everything, and 64-bit to the generic CRC (#210). Giving those two
# files sys/auxv.h and asm/hwcap.h (through android_hwcap.h) compiles their
# AT_HWCAP/AT_HWCAP2 branches, which ask the kernel, as on any Linux. cpu-features.h is still
# included, so its directory stays on the include path; nothing calls the cpufeatures library
# any more, so it isn't built.
if(ANDROID_ABI MATCHES "arm")
    target_include_directories(rapidyenc PRIVATE ${ANDROID_NDK}/sources/android/cpufeatures)
    set_property(
        SOURCE ${RAPIDYENC_SRC_DIR}/src/platform.cc ${RAPIDYENC_SRC_DIR}/src/crc.cc
        TARGET_DIRECTORY rapidyenc
        APPEND PROPERTY COMPILE_OPTIONS -include ${CMAKE_CURRENT_SOURCE_DIR}/android_hwcap.h
    )
endif()
