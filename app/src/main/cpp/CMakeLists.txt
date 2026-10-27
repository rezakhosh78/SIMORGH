cmake_minimum_required(VERSION 3.22.1)
project(simorghtun2proxybridge)

add_library(simorghtun2proxybridge SHARED native-lib.cpp)
find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(simorghtun2proxybridge ${log-lib} ${dl-lib})

# PingNG's native Desync engine. The source is kept under its original
# namespace-free C implementation and exposed through our own JNI wrapper.
file(GLOB PINGNG_SOURCES PingNG/*.c)
list(REMOVE_ITEM PINGNG_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/PingNG/win_service.c)
add_library(PingNG SHARED ${PINGNG_SOURCES} utils.c pingng-native.c)
target_include_directories(PingNG PRIVATE ${CMAKE_CURRENT_SOURCE_DIR} PingNG)
target_compile_options(PingNG PRIVATE -std=c99 -O2 -D_XOPEN_SOURCE=500)
target_compile_definitions(PingNG PRIVATE ANDROID_APP)
target_link_libraries(PingNG PRIVATE ${log-lib})
