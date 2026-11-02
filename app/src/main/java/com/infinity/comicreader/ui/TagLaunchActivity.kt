package com.infinity.comicreader.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Entry point for a tapped NFC tag. It has no screen: it hands the tag's link to the library
 * screen and finishes. Going through here (instead of launching the library screen directly)
 * means a tap while a book is open comes back to the existing library screen rather than
 * stacking a second copy of it on top.
 */
class TagLaunchActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = Intent(this, ListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Relaunching the task from Recents replays its original intent; that must not
        // re-run the tag.
        val fromHistory = (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (!fromHistory && savedInstanceState == null) target.data = intent.data
        startActivity(target)
        finish()
    }
}
