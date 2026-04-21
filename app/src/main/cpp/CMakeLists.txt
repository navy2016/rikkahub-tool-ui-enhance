cmake_minimum_required(VERSION 3.22.1)
project(rikkahub_pty)

add_library(rikkahub-pty SHARED rikkahub_pty.cpp)
find_library(log-lib log)
target_link_libraries(rikkahub-pty ${log-lib})
