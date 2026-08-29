cmake_minimum_required(VERSION 3.22.1)

project(time_machine_starter)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_compile_options(-fdata-sections -ffunction-sections -fno-exceptions -fno-rtti)

add_executable(libtime_machine_starter.so
        time_machine_starter.cpp)

if (NOT CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_options(libtime_machine_starter.so PRIVATE -Os -fvisibility=hidden)
    target_link_options(libtime_machine_starter.so PRIVATE -Wl,--gc-sections -Wl,--strip-all)
endif()
