package com.nullx11.official

import android.content.Context

/**
 * Tiny settings store. API keys never leave the device except in the
 * HTTPS request to the provider the user configured themselves.
 */
object Prefs {
    const val FILE = "nullx11"

    const val PROVIDER = "provider" // gemini | openrouter | groq | custom
    const val KEY_GEMINI = "key_gemini"
    const val KEY_OPENROUTER = "key_openrouter"
    const val KEY_GROQ = "key_groq"
    const val KEY_CUSTOM = "key_custom"
    const val BASE_CUSTOM = "base_custom"
    const val MODEL_GEMINI = "model_gemini"
    const val MODEL_OPENROUTER = "model_openrouter"
    const val MODEL_GROQ = "model_groq"
    const val MODEL_CUSTOM = "model_custom"
    const val TARGET_LANG = "target_lang"

    const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"
    const val DEFAULT_OPENROUTER_MODEL = "google/gemini-2.0-flash-exp:free"
    const val DEFAULT_GROQ_MODEL = "meta-llama/llama-3.3-70b-versatile"
    const val DEFAULT_CUSTOM_MODEL = ""
    const val DEFAULT_TARGET_LANG = "English"

    private val PROVIDER_KEYS = listOf("gemini", "openrouter", "groq", "custom")

    fun get(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun provider(c: Context): String =
        PROVIDER_KEYS.firstOrNull { it == get(c).getString(PROVIDER, "gemini") } ?: "gemini"

    fun apiKey(c: Context, provider: String): String = when (provider) {
        "gemini" -> get(c).getString(KEY_GEMINI, "")
        "openrouter" -> get(c).getString(KEY_OPENROUTER, "")
        "groq" -> get(c).getString(KEY_GROQ, "")
        else -> get(c).getString(KEY_CUSTOM, "")
    } ?: ""

    fun model(c: Context, provider: String): String = when (provider) {
        "gemini" -> get(c).getString(MODEL_GEMINI, DEFAULT_GEMINI_MODEL)
        "openrouter" -> get(c).getString(MODEL_OPENROUTER, DEFAULT_OPENROUTER_MODEL)
        "groq" -> get(c).getString(MODEL_GROQ, DEFAULT_GROQ_MODEL)
        else -> get(c).getString(MODEL_CUSTOM, DEFAULT_CUSTOM_MODEL)
    } ?: ""

    fun defaultModel(provider: String): String = when (provider) {
        "gemini" -> DEFAULT_GEMINI_MODEL
        "openrouter" -> DEFAULT_OPENROUTER_MODEL
        "groq" -> DEFAULT_GROQ_MODEL
        else -> DEFAULT_CUSTOM_MODEL
    }

    fun baseUrl(c: Context, provider: String): String = when (provider) {
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta/models/"
        "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
        "groq" -> "https://api.groq.com/openai/v1/chat/completions"
        else -> get(c).getString(BASE_CUSTOM, "").trim()
    } ?: ""

    fun targetLang(c: Context): String =
        get(c).getString(TARGET_LANG, DEFAULT_TARGET_LANG)?.takeIf { it.isNotBlank() } ?: DEFAULT_TARGET_LANG
}
