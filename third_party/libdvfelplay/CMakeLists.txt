# SPDX-License-Identifier: GPL-2.0-or-later
cmake_minimum_required(VERSION 3.18)
project(dvfelplay C CXX)

# libdovi (Rust, cargo-c): include/libdovi/rpu_parser.h, lib/libdovi.a
set(LIBDOVI_DIR "" CACHE PATH "libdovi install prefix for the target")
option(DVFP_BUILD_TEST "build the dvfp_test NativeActivity library" ON)

if(NOT LIBDOVI_DIR)
  message(FATAL_ERROR "set LIBDOVI_DIR")
endif()

add_library(dvfelplay SHARED src/dvfelplay.cpp src/felgpu.c src/module_install.cpp)
# the dvfel kernel module to bundle (dvfp_module_install)
set(DVFEL_KO "" CACHE FILEPATH "dvfel.ko to embed")
if(DVFEL_KO)
  enable_language(ASM)
  configure_file(src/module_blob.S.in ${CMAKE_CURRENT_BINARY_DIR}/module_blob.S @ONLY)
  target_sources(dvfelplay PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/module_blob.S)
  set_source_files_properties(${CMAKE_CURRENT_BINARY_DIR}/module_blob.S PROPERTIES OBJECT_DEPENDS ${DVFEL_KO})
  target_compile_definitions(dvfelplay PRIVATE DVFP_HAVE_KO)
endif()
# the dvfel kernel module for Amlogic SC2 (S905X4, Dune HD 5.4.125 firmwares)
set(DVFEL_KO_SC2 "" CACHE FILEPATH "SC2 dvfel.ko to embed")
if(DVFEL_KO_SC2)
  enable_language(ASM)
  configure_file(src/module_blob_sc2.S.in ${CMAKE_CURRENT_BINARY_DIR}/module_blob_sc2.S @ONLY)
  target_sources(dvfelplay PRIVATE ${CMAKE_CURRENT_BINARY_DIR}/module_blob_sc2.S)
  set_source_files_properties(${CMAKE_CURRENT_BINARY_DIR}/module_blob_sc2.S PROPERTIES OBJECT_DEPENDS ${DVFEL_KO_SC2})
  target_compile_definitions(dvfelplay PRIVATE DVFP_HAVE_KO_SC2)
endif()
target_include_directories(dvfelplay
  PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include
  PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src ${LIBDOVI_DIR}/include)
set_target_properties(dvfelplay PROPERTIES
  CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON
  C_VISIBILITY_PRESET hidden CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)
target_compile_options(dvfelplay PRIVATE -Wall -Wextra -Wno-unused-parameter -ffunction-sections -fdata-sections)
target_link_libraries(dvfelplay PRIVATE ${LIBDOVI_DIR}/lib/libdovi.a mediandk android EGL GLESv3 log dl m)
# only the dvfp_* API is exported; the static C++ runtime and libdovi stay private
target_link_options(dvfelplay PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections -Wl,-z,max-page-size=16384)

if(DVFP_BUILD_TEST)
  add_library(dvfptest SHARED test/dvfp_test.c test/el_probe.c)
  target_link_libraries(dvfptest PRIVATE dvfelplay mediandk android log)
  # ANativeActivity_onCreate must stay visible
  target_link_options(dvfptest PRIVATE -u ANativeActivity_onCreate -Wl,-z,max-page-size=16384)
endif()

# felbench: felgpu speed and bit exactness on dumped frames (run as root on the box)
add_executable(felbench tools/felbench.cpp src/felgpu.c)
target_include_directories(felbench PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
set_target_properties(felbench PROPERTIES CXX_STANDARD 17)
target_link_libraries(felbench PRIVATE EGL GLESv3 log m)
