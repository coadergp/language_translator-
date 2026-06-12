cmake_minimum_required(VERSION 3.22.1)
project(eartranslator_espeak)

# JNI bridge that calls into a prebuilt eSpeak-ng.
add_library(eartranslator_espeak SHARED espeak_jni.cpp)

# eSpeak-ng headers (you provide these under cpp/include/espeak-ng/).
target_include_directories(eartranslator_espeak PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)

# Prebuilt eSpeak-ng shared library, imported per ABI from src/main/jniLibs/<abi>/.
# ${ANDROID_ABI} is set by the Android Gradle plugin for each target ABI.
add_library(espeak-ng SHARED IMPORTED)
set_target_properties(espeak-ng PROPERTIES
    IMPORTED_LOCATION ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libespeak-ng.so)

find_library(log-lib log)

target_link_libraries(eartranslator_espeak
    espeak-ng
    ${log-lib})
