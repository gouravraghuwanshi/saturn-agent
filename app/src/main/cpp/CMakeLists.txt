cmake_minimum_required(VERSION 3.22.1)
project(piexec C)
# Exec shim preloaded into pi's processes (see piexec.c).
add_library(piexec SHARED piexec.c)
target_compile_options(piexec PRIVATE -std=c11 -Wall -Wextra -Werror -O2)
target_link_options(piexec PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(piexec dl)
