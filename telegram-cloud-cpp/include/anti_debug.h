#ifndef ANTI_DEBUG_H
#define ANTI_DEBUG_H

#include <cstdlib>

#ifdef _WIN32
#include <windows.h>
#include <tlhelp32.h>

namespace AntiDebug {

// Detectar debugger con IsDebuggerPresent (API estándar)
inline bool IsDebuggerPresentCheck() {
    return ::IsDebuggerPresent() != 0;
}

// Detectar remote debugger
inline bool CheckRemoteDebugger() {
    BOOL isRemoteDebugger = FALSE;
    CheckRemoteDebuggerPresent(GetCurrentProcess(), &isRemoteDebugger);
    return isRemoteDebugger != FALSE;
}

// Detectar herramientas de análisis comunes
inline bool DetectAnalysisTools() {
    const wchar_t* tools[] = {
        L"ollydbg.exe",
        L"x64dbg.exe",
        L"x32dbg.exe",
        L"windbg.exe",
        L"ida.exe",
        L"ida64.exe",
        L"idaq.exe",
        L"idaq64.exe",
        L"ghidra.exe",
        L"procmon.exe",
        L"procmon64.exe"
    };

    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
    if (snapshot == INVALID_HANDLE_VALUE) {
        return false;
    }

    PROCESSENTRY32W pe32;
    pe32.dwSize = sizeof(PROCESSENTRY32W);

    if (!Process32FirstW(snapshot, &pe32)) {
        CloseHandle(snapshot);
        return false;
    }

    do {
        for (const auto& tool : tools) {
            if (_wcsicmp(pe32.szExeFile, tool) == 0) {
                CloseHandle(snapshot);
                return true;
            }
        }
    } while (Process32NextW(snapshot, &pe32));

    CloseHandle(snapshot);
    return false;
}

// Verificación completa anti-debugging
inline bool PerformAntiDebugCheck() {
    // Solo en Release, en Debug permitir debugger
    #ifdef NDEBUG
        if (IsDebuggerPresentCheck()) return true;
        if (CheckRemoteDebugger()) return true;
        if (DetectAnalysisTools()) return true;
    #endif
    
    return false;
}

// Acción al detectar debugger
inline void OnDebuggerDetected() {
    // Salir silenciosamente
    ExitProcess(0);
}

// Macro para insertar checks anti-debug en puntos críticos
#define ANTI_DEBUG_CHECK() \
    do { \
        if (AntiDebug::PerformAntiDebugCheck()) { \
            AntiDebug::OnDebuggerDetected(); \
        } \
    } while(0)

} // namespace AntiDebug

#else // Linux/macOS

namespace AntiDebug {

inline bool PerformAntiDebugCheck() {
    return false;
}

inline void OnDebuggerDetected() {
    exit(0);
}

#define ANTI_DEBUG_CHECK() \
    do { \
        if (AntiDebug::PerformAntiDebugCheck()) { \
            AntiDebug::OnDebuggerDetected(); \
        } \
    } while(0)

} // namespace AntiDebug

#endif // _WIN32

#endif // ANTI_DEBUG_H
