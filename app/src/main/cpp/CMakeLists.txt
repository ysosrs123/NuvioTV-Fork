cmake_minimum_required(VERSION 3.22.1)
project(dovi_bridge LANGUAGES C CXX)

option(DOVI_ENABLE_LIBDOVI "Link against a real libdovi static library" OFF)
set(DOVI_LIBDOVI_STATIC_LIB "" CACHE FILEPATH "Path to libdovi static archive (.a)")
set(DOVI_LIBDOVI_INCLUDE_DIR "" CACHE PATH "Path to libdovi C headers")
set(DOVI_LIBDOVI_PREBUILT_ROOT "" CACHE PATH "Root path containing ABI folders for prebuilt libdovi artifacts")
# Gradle passes OFF when DOVI native is disabled; the project still builds libnuviothumb (below).
option(NUVIO_BUILD_DOVI "Build the Dolby Vision bridge (dovi_bridge)" ON)

find_library(log-lib log)

# Native Dolby Vision Profile 7 FEL output through the Amlogic amstream dual-layer port
# (core/player/amlfel). Self-contained: no libdovi, only the NDK log and android (ANativeWindow) libraries.
option(AML_FEL_NATIVE_ENABLED "Build optional Amlogic FEL bridge" OFF)
if(AML_FEL_NATIVE_ENABLED)
add_library(
    aml_fel
    SHARED
    aml_fel_native.cpp
)
target_compile_features(aml_fel PRIVATE cxx_std_17)
target_compile_options(aml_fel PRIVATE -Wall -Wextra -Werror)
target_link_options(aml_fel PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(aml_fel ${log-lib} android)
endif()

if (NUVIO_BUILD_DOVI)
add_library(
    dovi_bridge
    SHARED
    dovi_bridge.cpp
)

target_compile_features(dovi_bridge PRIVATE cxx_std_17)
target_compile_options(dovi_bridge PRIVATE -Wall -Wextra -Wno-unused-parameter)
target_link_options(dovi_bridge PRIVATE "-Wl,-z,max-page-size=16384")

set(_dovi_resolved_static_lib "")
set(_dovi_resolved_include_dir "")

if (DOVI_ENABLE_LIBDOVI)
    if (EXISTS "${DOVI_LIBDOVI_STATIC_LIB}")
        set(_dovi_resolved_static_lib "${DOVI_LIBDOVI_STATIC_LIB}")
        if (EXISTS "${DOVI_LIBDOVI_INCLUDE_DIR}")
            set(_dovi_resolved_include_dir "${DOVI_LIBDOVI_INCLUDE_DIR}")
        endif()
    else()
        set(_dovi_prebuilt_root "${DOVI_LIBDOVI_PREBUILT_ROOT}")
        if (NOT _dovi_prebuilt_root)
            get_filename_component(_repo_root "${CMAKE_CURRENT_LIST_DIR}/../../../.." ABSOLUTE)
            set(_dovi_prebuilt_root "${_repo_root}/DV7/libdovi")
        endif()

        set(_dovi_abi_dir "")
        if (ANDROID_ABI STREQUAL "arm64-v8a")
            set(_dovi_abi_dir "android-arm64")
        elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
            set(_dovi_abi_dir "android-armeabi-v7a")
        elseif (ANDROID_ABI STREQUAL "x86")
            set(_dovi_abi_dir "android-x86")
        elseif (ANDROID_ABI STREQUAL "x86_64")
            set(_dovi_abi_dir "android-x86_64")
        endif()

        if (_dovi_abi_dir)
            set(_dovi_candidate_static_lib "${_dovi_prebuilt_root}/${_dovi_abi_dir}/lib/libdovi.a")
            set(_dovi_candidate_include_dir "${_dovi_prebuilt_root}/${_dovi_abi_dir}/include")
            if (EXISTS "${_dovi_candidate_static_lib}")
                set(_dovi_resolved_static_lib "${_dovi_candidate_static_lib}")
                if (EXISTS "${_dovi_candidate_include_dir}")
                    set(_dovi_resolved_include_dir "${_dovi_candidate_include_dir}")
                endif()
            endif()
        endif()
    endif()
endif()

if (DOVI_ENABLE_LIBDOVI
    AND EXISTS "${_dovi_resolved_static_lib}")
    add_library(dovi_static STATIC IMPORTED)
    set_target_properties(dovi_static PROPERTIES IMPORTED_LOCATION "${_dovi_resolved_static_lib}")
    if (EXISTS "${_dovi_resolved_include_dir}")
        target_include_directories(dovi_bridge PRIVATE "${_dovi_resolved_include_dir}")
    endif()
    target_compile_definitions(dovi_bridge PRIVATE DOVI_REAL_LINKED=1)
    message(STATUS "libdovi enabled for ABI=${ANDROID_ABI} lib=${_dovi_resolved_static_lib}")
    target_link_libraries(dovi_bridge dovi_static ${log-lib})
else()
    target_compile_definitions(dovi_bridge PRIVATE DOVI_REAL_LINKED=0)
    if (DOVI_ENABLE_LIBDOVI)
        message(FATAL_ERROR "libdovi requested but no static library resolved for ABI=${ANDROID_ABI}.")
    endif()
    target_link_libraries(dovi_bridge ${log-lib})
endif()
endif() # NUVIO_BUILD_DOVI

# Seek thumbnails: keyframe decoder over an FFmpeg that is already in the APK, loaded at runtime with
# dlopen/dlsym (no FFmpeg code is linked or duplicated). Two backends (thumb_backend.h), each compiled
# against its own vendored headers, set PER SOURCE FILE so no struct layout crosses versions:
#   thumb_backend_mpv.cpp  -> MPV's FFmpeg master N-122742 (third_party/ffmpeg-a7522f3fef), primary;
#   thumb_backend_ff60.cpp -> FFmpeg 6.0 in libmediainfo.so (third_party/ffmpeg-n6.0), fallback.
add_library(
    nuviothumb
    SHARED
    thumb_shim.cpp
    thumb_backend_mpv.cpp
    thumb_backend_ff60.cpp
    thumbcolor.c
    thumbdovi.c
)
find_library(jnigraphics-lib jnigraphics)
target_include_directories(nuviothumb PRIVATE "${CMAKE_CURRENT_LIST_DIR}")
set_source_files_properties(thumb_backend_mpv.cpp PROPERTIES
    INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/third_party/ffmpeg-a7522f3fef")
set_source_files_properties(thumb_backend_ff60.cpp PROPERTIES
    INCLUDE_DIRECTORIES "${CMAKE_CURRENT_LIST_DIR}/third_party/ffmpeg-n6.0")
target_compile_features(nuviothumb PRIVATE cxx_std_17)
target_compile_options(nuviothumb PRIVATE -O2 -Wall -Wextra -Wno-deprecated-declarations -Wno-unused-parameter)
target_link_options(nuviothumb PRIVATE "-Wl,-z,max-page-size=16384")
target_link_libraries(nuviothumb ${log-lib} ${jnigraphics-lib} dl m)
