package com.aliucord.plugins

import android.app.AlertDialog
import android.content.Context
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.aliucord.Http
import com.aliucord.Utils
import com.aliucord.annotations.AliucordPlugin
import com.aliucord.entities.Plugin
import com.aliucord.patcher.after
import com.aliucord.utils.ReflectUtils
import com.discord.api.permission.Permission
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.emoji.WidgetEmojiSheet
import com.discord.widgets.emoji.WidgetEmojiSheetViewModel

@AliucordPlugin
class EmojiCloner : Plugin() {

    private val buttonTag = "emoji_cloner_button"

    override fun start(context: Context) {
        patcher.after<WidgetEmojiSheet>(
            "configureUI",
            WidgetEmojiSheetViewModel.ViewState::class.java
        ) { param ->
            try {
                val state = param.args[0]
                if (state.javaClass.simpleName != "Loaded") return@after

                val emoji = ReflectUtils.getField(state, "emojiCustom") ?: return@after
                val emojiId = ReflectUtils.invokeMethod(emoji, "getId").toString()
                val emojiName = ReflectUtils.invokeMethod(emoji, "getName") as String
                val animated = ReflectUtils.invokeMethod(emoji, "isAnimated") as Boolean

                val root = (param.thisObject as Fragment).requireView() as ViewGroup
                val container = findLinear(root) ?: return@after
                if (container.findViewWithTag<View>(buttonTag) != null) return@after

                val ctx = container.context
                val button = TextView(ctx).apply {
                    tag = buttonTag
                    text = "Clone to my server"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setPadding(48, 36, 48, 36)
                    setOnClickListener { pickGuild(ctx, emojiId, emojiName, animated) }
                }
                container.addView(button)
            } catch (e: Throwable) {
                logger.error("Failed to inject clone button", e)
            }
        }
    }

    override fun stop(context: Context) = patcher.unpatchAll()

    private fun findLinear(v: View): LinearLayout? {
        if (v is LinearLayout && v.orientation == LinearLayout.VERTICAL) return v
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) findLinear(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun pickGuild(ctx: Context, emojiId: String, name: String, animated: Boolean) {
        val perms = StoreStream.getPermissions().guildPermissions
        val guilds = StoreStream.getGuilds().guilds.values.filter {
            val p = perms[it.id] ?: return@filter false
            PermissionUtils.can(Permission.MANAGE_EMOJIS_AND_STICKERS, p)
        }

        if (guilds.isEmpty()) {
            Utils.showToast("No servers where you can manage emojis")
            return
        }

        AlertDialog.Builder(ctx)
            .setTitle("Clone :$name: to…")
            .setItems(guilds.map { it.name }.toTypedArray()) { _, which ->
                clone(guilds[which].id, emojiId, name, animated)
            }
            .show()
    }

    private fun clone(guildId: Long, emojiId: String, name: String, animated: Boolean) {
        Utils.threadPool.execute {
            try {
                val ext = if (animated) "gif" else "png"
                val bytes = Http.Request("https://cdn.discordapp.com/emojis/$emojiId.$ext?size=128")
                    .execute().stream().readBytes()

                val mime = if (animated) "image/gif" else "image/png"
                val dataUri = "data:$mime;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

                val res = Http.Request.newDiscordRNRequest("/guilds/$guildId/emojis", "POST")
                    .executeWithJson(mapOf("name" to name, "image" to dataUri))

                Utils.showToast(
                    if (res.ok()) "Cloned :$name:" else "Failed: ${res.statusCode} ${res.text()}"
                )
            } catch (e: Throwable) {
                logger.error("Clone failed", e)
                Utils.showToast("Clone failed: ${e.message}")
            }
        }
    }
}
