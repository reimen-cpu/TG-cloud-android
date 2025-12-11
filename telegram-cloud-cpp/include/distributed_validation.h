#ifndef DISTRIBUTED_VALIDATION_H
#define DISTRIBUTED_VALIDATION_H

// Declaraciones de funciones de validación distribuidas
// Estas funciones están definidas en diferentes módulos para mayor seguridad

// Funciones en src/config.cpp
bool verifySecurity();

// Funciones en src/filedownloader.cpp  
bool checkProtocol();

// Funciones en src/telegramhandler.cpp
bool checkSystem();

#endif // DISTRIBUTED_VALIDATION_H




