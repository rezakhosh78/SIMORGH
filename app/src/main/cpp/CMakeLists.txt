cmake_minimum_required(VERSION 3.22.1)
project(simorghtun2proxybridge)

add_library(simorghtun2proxybridge SHARED native-lib.cpp)
find_library(log-lib log)
find_library(dl-lib dl)
target_link_libraries(simorghtun2proxybridge ${log-lib} ${dl-lib})
