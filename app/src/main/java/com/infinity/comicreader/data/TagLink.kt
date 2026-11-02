package com.infinity.comicreader.data

import java.net.URLDecoder

/**
 * The short links stored on NFC tags. A tag holds only ~130 bytes, so a link is kept as small
 * as possible and non-ASCII folder names are written as they are (NDEF URI records are UTF-8)
 * instead of being percent-encoded to nine bytes per character.
 *
 *  - `comicreader://open?p=Comics/One Piece`   a book or folder (relative to shared storage)
 *  - `comicreader://continue`                  whatever was read last
 *  - `comicreader://profile?m=longstrip&n=1`   reader settings (mode, direction, fit, night, keep-on, brightness)
 *
 * Tag contents come from outside the app, so [parse] only accepts known keys and values.
 */
object TagLink {
    const val SCHEME = "comicreader"
    private const val PREFIX = "$SCHEME://"

    private val MODES = setOf("paged", "vertical", "longstrip")
    private val DIRS = setOf("ltr", "rtl")
    private val FITS = setOf("screen", "width", "height", "stretch")

    sealed class Action {
        data class OpenPath(val path: String) : Action()
        object Continue : Action()
        data class Profile(
            val mode: String?,
            val dir: String?,
            val fit: String?,
            val night: Boolean?,
            val keepOn: Boolean?,
            val brightness: Int?
        ) : Action()
    }

    fun openPath(path: String): String = "${PREFIX}open?p=${escape(path)}"

    fun continueReading(): String = "${PREFIX}continue"

    fun profile(
        mode: String? = null,
        dir: String? = null,
        fit: String? = null,
        night: Boolean? = null,
        keepOn: Boolean? = null,
        brightness: Int? = null
    ): String {
        val parts = ArrayList<String>()
        if (mode != null) parts += "m=$mode"
        if (dir != null) parts += "d=$dir"
        if (fit != null) parts += "f=$fit"
        if (night != null) parts += "n=${if (night) 1 else 0}"
        if (keepOn != null) parts += "k=${if (keepOn) 1 else 0}"
        if (brightness != null) parts += "b=$brightness"
        return "${PREFIX}profile?" + parts.joinToString("&")
    }

    /** The action a tag asks for, or null when the text is not one of ours or is malformed. */
    fun parse(link: String?): Action? {
        if (link == null || !link.startsWith(PREFIX, ignoreCase = true)) return null
        val rest = link.substring(PREFIX.length)
        val command = rest.substringBefore('?').trimEnd('/').lowercase()
        val params = parseQuery(if ('?' in rest) rest.substringAfter('?') else "")
        return when (command) {
            "continue" -> Action.Continue
            "open" -> params["p"]?.takeIf { it.isNotBlank() }?.let { Action.OpenPath(it) }
            "profile" -> {
                val action = Action.Profile(
                    mode = params["m"]?.takeIf { it in MODES },
                    dir = params["d"]?.takeIf { it in DIRS },
                    fit = params["f"]?.takeIf { it in FITS },
                    night = params["n"]?.let { it == "1" },
                    keepOn = params["k"]?.let { it == "1" },
                    brightness = params["b"]?.toIntOrNull()?.coerceIn(20, 100)
                )
                action.takeIf {
                    it.mode != null || it.dir != null || it.fit != null || it.night != null ||
                        it.keepOn != null || it.brightness != null
                }
            }
            else -> null
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val key = pair.substringBefore('=')
            val value = if ('=' in pair) pair.substringAfter('=') else ""
            val decoded = try {
                URLDecoder.decode(value, "UTF-8")
            } catch (e: IllegalArgumentException) {
                continue
            }
            out[key] = decoded
        }
        return out
    }

    /** Percent-encodes only what would break the link; everything else stays readable and short. */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            if (ch == '%' || ch == '&' || ch == '=' || ch == '+' || ch == '#' || ch == '?' || ch.isWhitespace() || ch.isISOControl()) {
                for (b in ch.toString().toByteArray(Charsets.UTF_8)) {
                    sb.append('%').append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
                }
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}
