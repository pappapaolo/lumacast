cmake_minimum_required(VERSION 3.22.1)
project(lumacast C CXX)

# third_party/ holds the git submodules; scripts/build-openssl.sh fills third_party/openssl-build/<abi>
get_filename_component(TP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party" ABSOLUTE)
set(SSL ${TP}/openssl-build/${ANDROID_ABI})
if(NOT EXISTS ${SSL}/lib/libcrypto.a)
    message(FATAL_ERROR "Run scripts/build-openssl.sh first (missing ${SSL}/lib/libcrypto.a)")
endif()
set(UX ${TP}/UxPlay/lib)

file(GLOB UX_SRC ${UX}/*.c ${UX}/playfair/*.c ${UX}/llhttp/*.c)
list(APPEND UX_SRC ${UX}/mdnsd/dnssd_mdnsd.c)
file(GLOB PLIST_SRC ${TP}/libplist/src/*.c ${TP}/libplist/libcnary/*.c)
set(ALAC_SRC
    ${TP}/alac/codec/ALACDecoder.cpp ${TP}/alac/codec/ALACBitUtilities.c
    ${TP}/alac/codec/ag_dec.c ${TP}/alac/codec/dp_dec.c
    ${TP}/alac/codec/matrix_dec.c ${TP}/alac/codec/EndianPortable.c)

add_library(crypto STATIC IMPORTED)
set_target_properties(crypto PROPERTIES IMPORTED_LOCATION ${SSL}/lib/libcrypto.a)

add_library(airplay SHARED
    ${UX_SRC} ${PLIST_SRC} ${ALAC_SRC}
    airplay_jni.c mdnsd_android.c alac_jni.cpp)

target_include_directories(airplay PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${UX} ${UX}/playfair ${UX}/llhttp ${UX}/mdnsd
    ${TP}/libplist/include ${TP}/libplist/src ${TP}/libplist/libcnary/include
    ${SSL}/include ${TP}/alac/codec)

target_compile_definitions(airplay PRIVATE
    STANDALONE TARGET_POSIX _LINUX _REENTRANT _GNU_SOURCE NOHOLD
    PLIST_210 PLIST_230 HAVE_CONFIG_H HAVE_STRNDUP HAVE_MEMMEM
    OPENSSL_API_COMPAT=0x10101000L)
target_compile_options(airplay PRIVATE -O2 -w)
target_link_libraries(airplay crypto log android)
target_link_options(airplay PRIVATE "-Wl,-z,max-page-size=16384")
