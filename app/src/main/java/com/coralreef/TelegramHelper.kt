package com.coralreef

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Optional Telegram alerts, sent straight to the Bot API using the user's OWN bot (created with
 * @BotFather) — no server in between. Everything runs on a background thread so it never blocks
 * the UI or the accessibility service.
 */
object TelegramHelper {

    private const val TAG = "CateringBot"
    private const val API = "https://api.telegram.org/bot"

    /** Silent no-op if Telegram alerts are off or the bot token/chat id aren't filled in. */
    fun send(ctx: Context, text: String, silent: Boolean = false) {
        val s = FilterSettings.load(ctx)
        if (!s.isTelegramConfigured()) return

        Thread {
            val ok = sendMessage(s.telegramBotToken, s.telegramNotifyChatId, text, silent)
            if (ok) {
                Log.d(TAG, "Telegram ✓")
                DasherAccessibilityService.instance?.log("Telegram sent ✓")
            } else {
                Log.w(TAG, "Telegram send failed")
                DasherAccessibilityService.instance?.log("Telegram failed — check bot token / chat ID")
            }
        }.start()
    }

    /** Sends a test message with the given credentials (not necessarily saved yet). */
    fun sendTest(botToken: String, chatId: String, onResult: (Boolean, String) -> Unit) {
        if (botToken.isBlank() || chatId.isBlank()) {
            onResult(false, "Enter both a bot token and a chat ID first")
            return
        }
        Thread {
            val ok = sendMessage(botToken, chatId, "✅ Bot connected! Notifications are working.")
            onResult(ok, if (ok) "Message sent!" else "Couldn't send — check the bot token and chat ID, and make sure you've messaged your bot at least once")
        }.start()
    }

    /**
     * Finds the chat id of whoever most recently messaged the bot, via getUpdates. Only one
     * device ever polls a user's own bot, so the single-poller limit isn't an issue here.
     * [onResult] receives null if nothing was found (user hasn't messaged the bot yet).
     */
    fun detectChatId(botToken: String, onResult: (String?) -> Unit) {
        if (botToken.isBlank()) { onResult(null); return }
        Thread {
            val chatId = try {
                val conn = URL("$API$botToken/getUpdates").openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout = 8_000
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val results = JSONObject(body).optJSONArray("result")
                var found: String? = null
                if (results != null) {
                    for (i in results.length() - 1 downTo 0) {
                        val update = results.getJSONObject(i)
                        val msg = update.optJSONObject("message") ?: update.optJSONObject("channel_post") ?: continue
                        val chat = msg.optJSONObject("chat") ?: continue
                        found = chat.get("id").toString()
                        break
                    }
                }
                found
            } catch (e: Exception) {
                Log.w(TAG, "getUpdates failed: ${e.message}")
                null
            }
            onResult(chatId)
        }.start()
    }

    private fun sendMessage(botToken: String, chatId: String, text: String, silent: Boolean = false): Boolean = try {
        val conn = URL("$API$botToken/sendMessage").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val form = "chat_id=${enc(chatId)}&text=${enc(text)}&disable_notification=$silent"
        conn.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
        conn.responseCode in 200..299
    } catch (e: Exception) {
        Log.w(TAG, "sendMessage failed: ${e.message}")
        false
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
