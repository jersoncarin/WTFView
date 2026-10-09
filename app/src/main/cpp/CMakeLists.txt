cmake_minimum_required(VERSION 3.10.2)
project("wtfview-native")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -O3 -fomit-frame-pointer -flto -DNDEBUG")

add_library(wtfview-native SHARED
    native_lz4.c
    lz4.c
)

find_library(log-lib log)
find_library(graphics-lib jnigraphics)

target_link_libraries(wtfview-native
    ${log-lib}
    ${graphics-lib}
)

