cmake_minimum_required(VERSION 3.22.1)

project("app")

add_library(
        nativelib
        SHARED
        nativelib.c)

target_link_libraries(nativelib)

target_link_options(nativelib PRIVATE "-Wl,-z,max-page-size=16384")

