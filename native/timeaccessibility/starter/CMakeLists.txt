cmake_minimum_required(VERSION 3.22.1)

project(time_machine_starter C)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)

add_compile_options(-fdata-sections -ffunction-sections)

add_executable(libtime_machine_starter.so
        time_machine_starter.c)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(libtime_machine_starter.so PRIVATE -Os -fvisibility=hidden)
    target_link_options(libtime_machine_starter.so PRIVATE -Wl,--gc-sections -Wl,--strip-all)
endif()
