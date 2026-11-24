cmake_minimum_required(VERSION 3.22)
project(atom_pty C)

if (NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif ()

add_library(atom_pty SHARED pty.c)
target_compile_options(atom_pty PRIVATE -Wall -Wextra -Werror=implicit-function-declaration -O2)
target_link_options(atom_pty PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(atom_pty log)
