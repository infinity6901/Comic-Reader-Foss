package com.infinity.comicreader.util

import android.app.Activity
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Writes one link to the next NFC tag held against the phone. Reader mode is on only between
 * [start] and [stop], and only while [activity] is in the foreground, so ordinary tag
 * launches are not swallowed. [onResult] runs on the main thread.
 */
class TagWriter(
    private val activity: Activity,
    private val link: String,
    private val onResult: (Result) -> Unit
) {
    enum class Result { WRITTEN, TOO_BIG, READ_ONLY, FAILED }

    private val adapter: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** False when the phone has no NFC or it is switched off. */
    fun start(): Boolean {
        val nfc = adapter ?: return false
        if (!nfc.isEnabled) return false
        nfc.enableReaderMode(activity, { tag -> handle(tag) }, NfcAdapter.FLAG_READER_NFC_A, null)
        return true
    }

    fun stop() {
        val nfc = adapter ?: return
        try {
            nfc.disableReaderMode(activity)
        } catch (e: IllegalStateException) {
            // The activity is already going away; reader mode ends with it.
        }
    }

    /** Reader mode can report the same tag twice before the dialog closes; write only once. */
    private val done = AtomicBoolean(false)

    private fun handle(tag: Tag) {
        if (!done.compareAndSet(false, true)) return
        val result = write(tag)
        if (result == Result.WRITTEN) {
            // Reader mode ends right after this; without it the tag still in the field would be
            // rediscovered and open the comic that was just written to it.
            try {
                adapter?.ignore(tag, IGNORE_MS, null, null)
            } catch (e: RuntimeException) {
                // Best effort only.
            }
        } else {
            // A failed attempt (tag moved away, too small, locked) may be retried with another tag.
            done.set(false)
        }
        activity.runOnUiThread { onResult(result) }
    }

    private companion object {
        const val IGNORE_MS = 2000
    }

    private fun write(tag: Tag): Result {
        val message = NdefMessage(arrayOf(NdefRecord.createUri(link)))
        val size = message.toByteArray().size
        return try {
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                ndef.connect()
                try {
                    when {
                        !ndef.isWritable -> Result.READ_ONLY
                        ndef.maxSize < size -> Result.TOO_BIG
                        else -> {
                            ndef.writeNdefMessage(message)
                            Result.WRITTEN
                        }
                    }
                } finally {
                    try { ndef.close() } catch (e: java.io.IOException) { /* nothing to recover */ }
                }
            } else {
                val formatable = NdefFormatable.get(tag) ?: return Result.FAILED
                formatable.connect()
                try {
                    formatable.format(message)
                    Result.WRITTEN
                } finally {
                    try { formatable.close() } catch (e: java.io.IOException) { /* nothing to recover */ }
                }
            }
        } catch (e: Exception) {
            // IOException (tag moved away), FormatException, SecurityException, ...
            Result.FAILED
        }
    }
}
