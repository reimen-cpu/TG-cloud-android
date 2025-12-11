#ifndef STRING_OBFUSCATION_H
#define STRING_OBFUSCATION_H

#include <string>
#include <algorithm>

// XOR simple para ofuscar strings en tiempo de compilación
class ObfString {
private:
    char* data;
    size_t len;
    char key;

public:
    template<size_t N>
    constexpr ObfString(const char(&str)[N], char xorKey = 0x7F) : len(N-1), key(xorKey) {
        data = new char[N];
        for(size_t i = 0; i < N-1; i++) {
            data[i] = str[i] ^ key;
        }
        data[N-1] = '\0';
    }

    std::string decrypt() const {
        std::string result;
        result.reserve(len);
        for(size_t i = 0; i < len; i++) {
            result += (data[i] ^ key);
        }
        return result;
    }

    ~ObfString() {
        // Limpiar memoria
        if(data) {
            std::fill_n(data, len, 0);
            delete[] data;
        }
    }
};

// Macro para usar strings ofuscados
#define OBF_STR(str) (ObfString(str).decrypt())

// Macro para strings críticos con key custom
#define OBF_STR_KEY(str, key) (ObfString(str, key).decrypt())

#endif // STRING_OBFUSCATION_H





