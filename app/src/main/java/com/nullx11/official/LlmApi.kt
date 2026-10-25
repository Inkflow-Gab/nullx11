package com.nullx11.official

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The single engine behind both floating windows:
 *  - screenTranslate(): vision request that returns text blocks + positions
 *  - chat():            multi-turn AI conversation for the Chat tab
 *  - translateText():   one-shot bulk translation for the Translate tab
 *
 * Providers with free tiers are kept behind one uniform interface:
 *  - Gemini AI Studio      (own wire format, api key header)
 *  - OpenRouter | Groq | custom OpenAI-compatible endpoints
 */
object LlmApi {

    /** One visible text block found on screen. Coordinates normalized 0..1000. */
    data class Chip(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val orig: String,
        val translated: String,
        val lang: String,
    )

    class ApiException(message: String) : Exception(message)

    private enum class Style { GEMINI, OPENAI }

    private data class Provider(val style: Style, val url: String, val key: String, val model: String)

    // ------------------------------------------------------------ providers

    private fun providerConfig(ctx: Context): Provider {
        val prov = Prefs.provider(ctx)
        val key = Prefs.apiKey(ctx, prov).trim()
        if (key.isBlank()) throw ApiException("No API key yet — open the Settings tab.")
        val model = Prefs.model(ctx, prov).trim()
        if (model.isBlank()) throw ApiException("No model set — open the Settings tab.")
        val base = Prefs.baseUrl(ctx, prov).trim()
        if (base.isBlank()) throw ApiException("Base URL is empty — open the Settings tab.")
        val url = if (prov == "gemini") {
            base.trimEnd('/') + "/" + model + ":generateContent"
        } else {
            base.trimEnd('/') + "/chat/completions"
        }
        val style = if (prov == "gemini") Style.GEMINI else Style.OPENAI
        return Provider(style, url, key, model)
    }

    // ---------------------------------------------------------- screen mode

    /**
     * Reads the screenshot, translates every text block and returns chips with
     * their position so the service can place cards exactly over the source.
     */
    fun screenTranslate(ctx: Context, bitmap: Bitmap): List<Chip> {
        val p = providerConfig(ctx)
        val target = Prefs.targetLang(ctx)
        val sys = "You are the screen-reading engine of NullX11, a floating screen translator used inside chat apps. " +
            "Look at the screenshot and find every meaningful visible text block (chat messages, usernames, buttons, menus, captions). " +
            "Detect the language automatically and translate each block into $target."
        val user = "Return ONLY minified JSON (no markdown fences) with this exact shape: " +
            "{\"items\":[{\"box\":[ymin,xmin,ymax,xmax],\"orig\":\"original text\",\"lang\":\"detected language code\",\"tr\":\"translation in $target\"}]}. " +
            "The \"box\" is the block rectangle; all four coordinates are normalized 0..1000 relative to THIS image, in the order [ymin, xmin, ymax, xmax]. " +
            "Merge consecutive lines of one paragraph into a single item. Skip blurry, empty or purely decorative text."
        val b64 = encode(bitmap)
        val raw = call(p, sys, listOf("user" to user), b64, jsonMode = true)
        val json = JSONObject(extractJson(raw))
        val items = json.optJSONArray("items") ?: JSONArray()

        val out = ArrayList<Chip>()
        for (i in 0 until items.length()) {
            val o = items.optJSONObject(i) ?: continue
            val box = o.optJSONArray("box") ?: continue
            if (box.length() < 4) continue
            // documented Gemini convention: [ymin, xmin, ymax, xmax]
            var y0 = clamp1000(box.optInt(0))
            var x0 = clamp1000(box.optInt(1))
            var y1 = clamp1000(box.optInt(2))
            var x1 = clamp1000(box.optInt(3))
            if (y1 - y0 < 6 || x1 - x0 < 6) continue
            val tr = o.optString("tr").trim()
            if (tr.isBlank()) continue
            val orig = o.optString("orig").trim().take(240)
            val lang = o.optString("lang").trim().take(16)
            out.add(Chip(x0, y0, x1, y1, orig, tr, lang))
        }
        if (out.isEmpty()) throw ApiException("No text blocks found — try shooting again.")
        return out
    }

    // ------------------------------------------------------------ chat tab

    /** Multi-turn AI chat. history = list of (role, content), role "user"|"assistant". */
    fun chat(ctx: Context, history: List<Pair<String, String>>): String {
        val p = providerConfig(ctx)
        val sys = "You are NullX11's Chat assistant: a friendly, concise live translator and conversation helper for chat apps " +
            "(QQ, WeChat, Discord, games). Reply in the same language the user writes in. " +
            "You are great at translating slang, memes and gaming terms. Keep answers short unless asked otherwise."
        return call(p, sys, history, null, jsonMode = false).trim()
    }

    // ------------------------------------------------------ text translate

    /** One-shot text translation. Returns detected source language to translation. */
    fun translateText(ctx: Context, text: String): Pair<String, String> {
        val p = providerConfig(ctx)
        val target = Prefs.targetLang(ctx)
        val sys = "You translate text precisely for a chat tool. Detect the source language automatically and translate into $target. " +
            "Return ONLY minified JSON: {\"lang\":\"detected language code\",\"tr\":\"the translation\"} — no markdown fences."
        val raw = call(p, sys, listOf("user" to text), null, jsonMode = true)
        val json = JSONObject(extractJson(raw))
        val lang = json.optString("lang", "").trim()
        val tr = json.optString("tr", "").trim()
        if (tr.isBlank()) throw ApiException("Empty translation.")
        return Pair(lang.ifBlank { "?" }, tr)
    }

    // --------------------------------------------------------------- core

    /**
     * Uniform request across providers.
     * history items keep the LAST user message where an optional image is
     * attached. jsonMode asks the provider for strict JSON output when
     * supported; the caller always re-extracts the JSON blob defensively.
     */
    private fun call(
        p: Provider,
        sys: String,
        history: List<Pair<String, String>>,
        imageB64: String?,
        jsonMode: Boolean,
    ): String {
        val body: String = when (p.style) {
            Style.GEMINI -> buildGemini(sys, history, imageB64, jsonMode)
            else -> buildOpenAi(p.model, sys, history, imageB64)
        }
        val raw = post(p, body)
        return parseReply(p, raw)
    }

    private fun buildGemini(
        sys: String,
        history: List<Pair<String, String>>,
        imageB64: String?,
        jsonMode: Boolean,
    ): String {
        val contents = JSONArray()
        history.forEachIndexed { idx, (role, text) ->
            val parts = JSONArray().put(JSONObject().put("text", text))
            if (idx == history.lastIndex && role == "user" && imageB64 != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", imageB64)
                    )
                )
            }
            contents.put(
                JSONObject().put("role", if (role == "user") "user" else "model").put("parts", parts)
            )
        }
        val gen = JSONObject().put("temperature", if (imageB64 != null) 0.15 else 0.7)
        if (jsonMode) gen.put("response_mime_type", "application/json")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", gen)
        return body.toString()
    }

    private fun buildOpenAi(
        model: String,
        sys: String,
        history: List<Pair<String, String>>,
        imageB64: String?,
    ): String {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        history.forEachIndexed { idx, (role, text) ->
            val oaiRole = if (role == "user") "user" else "assistant"
            val modele = JSONObject().put("role", oaiRole)
            if (idx == history.lastIndex && role == "user" && imageB64 != null) {
                modele.put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", text))
                        .put(
                            JSONObject().put(
                                "type", "image_url"
                            ).put(
                                "image_url",
                                JSONObject().put("url", "data:image/jpeg;base64,$imageB64")
                            )
                        )
                )
            } else {
                modele.put("content", text)
            }
            messages.put(modele)
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", if (imageB64 != null) 0.15 else 0.7)
        return body.toString()
    }

    private fun post(p: Provider, body: String): String {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(p.url).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 180000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            when (p.style) {
                Style.GEMINI -> conn.setRequestProperty("x-goog-api-key", p.key)
                else -> conn.setRequestProperty("Authorization", "Bearer ${p.key}")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw ApiException("HTTP $code — ${text.take(240)}")
            }
            text
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw ApiException(e.message ?: "Network error")
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseReply(p: Provider, raw: String): String {
        return try {
            when (p.style) {
                Style.GEMINI -> {
                    val candidates = JSONObject(raw).optJSONArray("candidates")
                    val first = candidates?.optJSONObject(0)
                    val parts = first?.optJSONObject("content")?.optJSONArray("parts")
                    if (parts != null) {
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
                        }
                        sb.toString()
                    } else ""
                }
                else -> {
                    val choices = JSONObject(raw).optJSONArray("choices")
                    val msg = choices?.optJSONObject(0)?.optJSONObject("message")
                    msg?.optString("content").orEmpty()
                }
            }
        } catch (e: Exception) {
            throw ApiException("Unexpected reply — ${e.message}")
        }.ifBlank { throw ApiException("Empty reply from the model.") }
    }

    // ------------------------------------------------------------- helpers

    /** Survives markdown fences and chatty models. */
    private fun extractJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        }
        val first = s.indexOf('{')
        val last = s.lastIndexOf('}')
        return if (first >= 0 && last > first) s.substring(first, last + 1) else s
    }

    private fun clamp1000(v: Int): Int = v.coerceIn(0, 1000)

    /** Downscale + JPEG + base64 (NO_WRAP) to keep payloads small. */
    private fun encode(bitmap: Bitmap): String {
        val maxSide = 1280
        val longSide = maxOf(bitmap.width, bitmap.height)
        val bmi = if (longSide > maxSide) {
            val k = maxSide.toFloat() / longSide
            val w = (bitmap.width * k).toInt().coerceAtLeast(1)
            val h = (bitmap.height * k).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        } else bitmap
        val bos = ByteArrayOutputStream()
        bmi.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        val data = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        if (bmi !== bitmap) bitmap.recycle()
        return data
    }
}
