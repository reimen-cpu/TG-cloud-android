package com.telegram.cloud.util

import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

data class Language(
    val code: String,
    val name: String,
    val flagEmoji: String
)

object LanguageManager {
    
    val supportedLanguages = listOf(
        Language("en", "English", "🇺🇸"),
        Language("es", "Español", "🇪🇸")
    )
    
    fun setLanguage(languageCode: String) {
        val appLocale = LocaleListCompat.forLanguageTags(languageCode)
        AppCompatDelegate.setApplicationLocales(appLocale)
    }
    
    fun getCurrentLanguage(): String {
        return if (!AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.getApplicationLocales()[0]?.language ?: "en"
        } else {
            Locale.getDefault().language
        }
    }
    
    fun getLanguageByCode(code: String): Language {
        return supportedLanguages.find { it.code == code } 
            ?: supportedLanguages.first()
    }
}
