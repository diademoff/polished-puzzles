cmake_minimum_required(VERSION 3.16)

# Host build of the parity oracle.
#
# The sources come from the vendored tree. ORACLE_SRC, ORACLE_CORE and
# ORACLE_GAMES are filled by tools/oracle/build.sh, which reads
# app/src/main/jni/CMakeLists.txt. There is no second copy of the game
# or source lists.

project(puzzles-oracle C)

if(NOT ORACLE_SRC)
  message(FATAL_ERROR "ORACLE_SRC is not set; run tools/oracle/build.sh")
endif()

set(CMAKE_C_STANDARD 99)
set(CMAKE_C_STANDARD_REQUIRED ON)

# The app build explicitly keeps assertions (-DNDEBUG is stripped in
# cmake/setup.cmake). The oracle must behave the same, so its release
# flags must not define NDEBUG either.
set(CMAKE_C_FLAGS_RELEASE "-O2")
set(CMAKE_C_FLAGS_MINSIZEREL "-Os")

# The oracle must describe the same engine as the Android app, so it
# uses the same compile definitions as cmake/platforms/android.cmake.
# Without them, presets and key handling would differ and every parity
# test would compare two different programs.
add_compile_definitions(
  ANDROID
  COMBINED
  SMALL_SCREEN
  STYLUS_BASED
  NO_PRINTING
  VIVID_COLOURS
)

include_directories(
  ${ORACLE_SRC}
  ${CMAKE_CURRENT_SOURCE_DIR}
  ${CMAKE_CURRENT_BINARY_DIR}/include
)

set(core_sources)
foreach(name IN LISTS ORACLE_CORE)
  list(APPEND core_sources ${ORACLE_SRC}/${name})
endforeach()

set(game_sources)
foreach(name IN LISTS ORACLE_GAMES)
  list(APPEND game_sources ${ORACLE_SRC}/${name}.c)
endforeach()

# hat.c and spectre.c are part of the upstream 'common' library but not
# of 'core_obj'. list.c supplies gamelist[] and gamenames[].
add_executable(puzzles-oracle
  oracle.c
  android_stubs.c
  ${ORACLE_SRC}/list.c
  ${ORACLE_SRC}/hat.c
  ${ORACLE_SRC}/spectre.c
  ${core_sources}
  ${game_sources}
)

target_link_libraries(puzzles-oracle m)

# The upstream fuzz frontend. It reads one save file from stdin, loads
# it, draws through the null API, and writes the re-serialised state.
#
# fuzzpuzz links nullfe.c instead of drawing.c, like the upstream
# command-line programs. drawing.c and nullfe.c define the same
# drawing symbols, so both cannot be linked into one binary.
option(ORACLE_BUILD_FUZZ "Also build the fuzzpuzz stand-alone front end" OFF)
if(ORACLE_BUILD_FUZZ)
  set(fuzz_core_sources)
  foreach(name IN LISTS ORACLE_CORE)
    if(NOT name STREQUAL "drawing.c")
      list(APPEND fuzz_core_sources ${ORACLE_SRC}/${name})
    endif()
  endforeach()

  add_executable(fuzzpuzz
    ${ORACLE_SRC}/fuzzpuzz.c
    ${ORACLE_SRC}/nullfe.c
    fuzz_extra.c
    android_stubs.c
    ${ORACLE_SRC}/list.c
    ${ORACLE_SRC}/hat.c
    ${ORACLE_SRC}/spectre.c
    ${fuzz_core_sources}
    ${game_sources}
  )
  target_link_libraries(fuzzpuzz m)
endif()
