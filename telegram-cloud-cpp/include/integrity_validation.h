#ifndef INTEGRITY_VALIDATION_H
#define INTEGRITY_VALIDATION_H

// Declaraciones externas de constantes de validación distribuidas
// Estas constantes están definidas en diferentes módulos para mayor seguridad

// Parte 1/3 - definidas en config.cpp
extern const char* VALIDATION_TOKEN_A;
extern const char* VALIDATION_TOKEN_B;

// Parte 2/3 - definidas en fileuploader.cpp  
extern const char* INTEGRITY_MARKER_C;
extern const char* INTEGRITY_MARKER_D;
extern const char* INTEGRITY_MARKER_E;

// Parte 3/3 - definidas en filedownloader.cpp
extern const char* SECURITY_FLAG_F;
extern const char* SECURITY_FLAG_G;

#endif // INTEGRITY_VALIDATION_H




