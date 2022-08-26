cmake_minimum_required(VERSION 3.24)

if(EXISTS "${CMAKE_CURRENT_BINARY_DIR}/CMakeLists.txt")
    message(
        FATAL_ERROR
            "In-source builds are not allowed, please create a 'build' subfolder and use `cmake ..` inside it.\n"
            "NOTE: cmake will now create CMakeCache.txt and CMakeFiles/*.\n"
            "You must delete them, or cmake will refuse to work.")
endif()

option(USE_CODE_TIDY "Use code static analysis on build" ON)

project(jni-test)

if("${CMAKE_PROJECT_NAME}" STREQUAL "${PROJECT_NAME}")
    set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
    set_property(GLOBAL PROPERTY USE_FOLDERS ON)
endif()

find_package(JNI 11 REQUIRED)

add_library(
    jniTest SHARED
    cpp/JNI/JNIClass.cpp
    cpp/JNI/JNIEnv.cpp
    cpp/JNI/JNIString.cpp
    cpp/Main.cpp
    cpp/TestConstructors.cpp
    cpp/TestDuplexCalls.cpp
    cpp/TestFields.cpp
    cpp/TestMethods.cpp
    cpp/TestObjectArrays.cpp
    cpp/TestScalarArrays.cpp
    cpp/TestStaticFields.cpp
    cpp/TestStaticMethods.cpp)
target_compile_features(jniTest PRIVATE cxx_std_17)
target_compile_options(jniTest PRIVATE -Wall -Werror)
target_link_libraries(jniTest PRIVATE JNI::JNI)

if((CMAKE_CXX_COMPILER_ID STREQUAL "GNU") AND (CMAKE_CXX_COMPILER_VERSION VERSION_GREATER_EQUAL 11))
    target_compile_options(jniTest PRIVATE -std=c++17)
endif()

if(USE_CODE_TIDY)
    find_program(
        CLANG_TIDY_EXE
        NAMES clang-tidy-14 clang-tidy
        DOC "Path to clang-tidy executable")
    if(CLANG_TIDY_EXE)
        message(STATUS "Using clang-tidy from ${CLANG_TIDY_EXE}")
        set_target_properties(jniTest PROPERTIES CXX_CLANG_TIDY "${CLANG_TIDY_EXE}")
    else()
        message(WARNING "clang-tidy not found, please install tool and relaunch configuration")
    endif()
endif()

find_package(Java 11 REQUIRED)
include(UseJava)
add_jar(
    JavaJNITest
    java/jni/Test.java
    java/jni/TestConstructors.java
    java/jni/TestDuplexCalls.java
    java/jni/TestFields.java
    java/jni/TestMethods.java
    java/jni/TestObjectArrays.java
    java/jni/TestScalarArrays.java
    java/jni/TestStaticFields.java
    java/jni/TestStaticMethods.java
    ENTRY_POINT jni.Test)

add_custom_target(
    run
    COMMAND "${Java_JAVA_EXECUTABLE}" -Djava.library.path=. -jar JavaJNITest.jar
    WORKING_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}"
    DEPENDS jniTest JavaJNITest)
