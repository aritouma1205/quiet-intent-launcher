package io.github.aritouma1205.quietintentlauncher.search

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import android.os.Build
import androidx.core.net.toUri
import io.github.aritouma1205.quietintentlauncher.settings.WebSearchEngine
import java.net.URLEncoder

/**
 * External search / share hand-off (design 9.2). Fires only on explicit user
 * action: a fixed HTTPS search URL in the default browser, or a standard
 * ACTION_SEND text share. No private deep links, no clipboard writes, and
 * the query is never sent anywhere while typing.
 */
class ExternalSearch(private val context: Context) {

    /** Result of an external hand-off attempt. */
    sealed interface Outcome {
        data object Launched : Outcome

        /** No browser / no share receiver available. */
        data object NoHandler : Outcome

        data class Failed(val cause: String) : Outcome
    }

    /** Web検索: the configured engine's HTTPS URL, query URL-encoded. */
    fun webSearch(query: String, engine: WebSearchEngine): Outcome {
        val url = webSearchUrl(engine, query) ?: return Outcome.Failed("empty query")
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (resolveCount(intent) == 0) return Outcome.NoHandler
        return try {
            context.startActivity(intent)
            Outcome.Launched
        } catch (e: ActivityNotFoundException) {
            Outcome.NoHandler
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "web search failed")
        }
    }

    /**
     * ChatGPT送信用の共有。ChatGPTのアプリがACTION_SENDを受けられるときだけ
     * そのパッケージへ直接送る。戻り値がnullのときは非対応 — UIは
     * 「共有先を選ぶ」に切り替える。
     */
    fun shareToChatGpt(query: String): Outcome? {
        val hasReceiver = shareReceivers()
            .any { it.activityInfo?.packageName == CHATGPT_PACKAGE }
        if (!hasReceiver) return null
        val intent = shareIntent(query)
            .setPackage(CHATGPT_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            Outcome.Launched
        } catch (e: ActivityNotFoundException) {
            Outcome.NoHandler
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "share failed")
        }
    }

    /**
     * ChatGPTが受け手として見えるときだけ true（design 9.2: 対応する受け手が
     * あるときだけ送信候補を提示する）。
     */
    fun isChatGptAvailable(): Boolean =
        shareReceivers().any { it.activityInfo?.packageName == CHATGPT_PACKAGE }

    /** Any ACTION_SEND receiver exists (Chooser に渡せるか）。 */
    fun canShare(): Boolean = shareReceivers().isNotEmpty()

    /** 「共有先を選ぶ」: the system chooser; the user picks the destination. */
    fun shareWithChooser(query: String): Outcome {
        if (!canShare()) return Outcome.NoHandler
        val chooser = Intent.createChooser(shareIntent(query), null)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(chooser)
            Outcome.Launched
        } catch (e: ActivityNotFoundException) {
            Outcome.NoHandler
        } catch (e: Exception) {
            Outcome.Failed(e.message ?: "share failed")
        }
    }

    private fun shareIntent(query: String): Intent =
        Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, query)

    private fun shareReceivers(): List<android.content.pm.ResolveInfo> {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
        return try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.queryIntentActivities(
                    intent,
                    ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_ALL,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun resolveCount(intent: Intent): Int = try {
        if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.queryIntentActivities(
                intent,
                ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_ALL,
            )
        }.size
    } catch (e: Exception) {
        0
    }

    companion object {
        /** The public Play-store package of ChatGPT. */
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"

        /**
         * Fixed HTTPS search endpoints (design 9.2). The query is form-encoded
         * (space -> +, per application/x-www-form-urlencoded); an empty query
         * maps to null so callers can refuse the hand-off. Pure JVM so the
         * URL shape is unit-testable.
         */
        fun webSearchUrl(engine: WebSearchEngine, query: String): String? {
            if (query.isBlank()) return null
            @Suppress("DEPRECATION")
            val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
            return when (engine) {
                WebSearchEngine.Google -> "https://www.google.com/search?q=$encoded"
                WebSearchEngine.Bing -> "https://www.bing.com/search?q=$encoded"
                WebSearchEngine.DuckDuckGo -> "https://duckduckgo.com/?q=$encoded"
            }
        }
    }
}
