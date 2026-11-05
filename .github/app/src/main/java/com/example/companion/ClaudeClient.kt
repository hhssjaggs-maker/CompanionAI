package com.example.companion

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object ClaudeClient {
    private const val SYSTEM =
        "You are Neon, a witty cyberpunk desktop companion living on the user's phone screen. " +
        "Reply in the same language the user writes in. Keep answers short (1-3 sentences) unless asked for more."

    fun chat(apiKey: String, history: JSONArray): Result<String> = runCatching {
        val c = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 60000
        c.doOutput = true
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-api-key", apiKey)
        c.setRequestProperty("anthropic-version", "2023-06-01")
        val body = JSONObject()
            .put("model", "claude-sonnet-4-6")
            .put("max_tokens", 1000)
            .put("system", SYSTEM)
            .put("messages", history)
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val text = stream.bufferedReader().readText()
        if (code !in 200..299) error("HTTP $code: $text")
        JSONObject(text).getJSONArray("content").getJSONObject(0).getString("text")
    }
}
