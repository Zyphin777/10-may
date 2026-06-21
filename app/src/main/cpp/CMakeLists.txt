cmake_minimum_required(VERSION 3.22.1)

project(masaar)

add_library(
        masaar
        SHARED
        ${CMAKE_CURRENT_SOURCE_DIR}/masaar_core.cpp
        ${CMAKE_CURRENT_SOURCE_DIR}/masaar_jni.cpp
)

find_library(
        log-lib
        log
)

target_link_libraries(
        masaar
        ${log-lib}
)