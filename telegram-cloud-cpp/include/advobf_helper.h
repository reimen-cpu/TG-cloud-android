#ifndef ADVOBF_HELPER_H
#define ADVOBF_HELPER_H

#ifdef TELEGRAMCLOUD_ANDROID
// Android: no usar advobfuscator, devolver string sin ofuscar
#define OBFSTR(str) std::string(str)
#define OBFAES(str) std::string(str)
#else
#include <advobfuscator/string.h>
#include <advobfuscator/aes_string.h>
#include <advobfuscator/format.h>

// Usar namespace para facilitar uso
using namespace andrivet::advobfuscator;

// Macros para ofuscación rápida
#define OBFSTR(str) str##_obf
#define OBFAES(str) str##_aes
#endif

#endif // ADVOBF_HELPER_H






