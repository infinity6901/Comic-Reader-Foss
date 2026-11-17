package com.infinity.comicreader.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.infinity.comicreader.R
import com.infinity.comicreader.data.Bookmark
import com.infinity.comicreader.data.HistoryItem
import com.infinity.comicreader.data.Library
import com.infinity.comicreader.data.Prefs
import com.infinity.comicreader.data.SeriesSettings
import com.infinity.comicreader.reader.Filters
import com.infinity.comicreader.reader.FolderImageSource
import com.infinity.comicreader.reader.PageSource
import com.infinity.comicreader.reader.PageSources
import com.infinity.comicreader.util.AppDispatchers
import com.infinity.comicreader.util.ChopDetector
import com.infinity.comicreader.util.ComicInfo
import com.infinity.comicreader.util.FileUtils
import com.infinity.comicreader.util.Formats
import com.infinity.comicreader.util.NaturalOrder
import com.infinity.comicreader.util.SpreadLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.BitSet
import kotlin.math.abs

/**
 * The reader.
 *
 * Page layout is one of three modes — paged, vertical or long strip — and every
 * reader option (mode, fit, filter, brightness, spacing, direction, two-page,
 * cover-solo, night filter, keep-on, fullscreen, mini HUD) is read from and
 * written back to [Prefs], so choices survive chapter changes and app restarts.
 */
class ViewerActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var library: Library

    private lateinit var pager: ViewPager2
    private lateinit var vertical: RecyclerView
    private lateinit var toolbar: Toolbar
    private lateinit var bottomBar: LinearLayout
    private lateinit var hudView: TextView
    private lateinit var seek: SeekBar
    private lateinit var pageText: TextView
    private lateinit var chapterText: TextView
    private lateinit var dim: View
    private lateinit var nightOverlay: View
    private lateinit var progress: android.widget.ProgressBar
    private lateinit var endCard: View
    private lateinit var endSubtitle: TextView
    private lateinit var endNext: View
    private lateinit var endPrev: View
    private lateinit var btnPrevChapter: ImageButton
    private lateinit var btnNextChapter: ImageButton

    private var source: PageSource? = null
    private var adapter: PageAdapter? = null
    private var file: File? = null
    private var comicInfo: ComicInfo? = null

    private var stripAhead: PageSource? = null
    private var stripAheadFile: File? = null
    private var stripAheadJob: Job? = null
    private var stripRollPending = false

    /** Sibling chapters (openable files or image-chapter folders in the same parent), natural-ordered. */
    private var chapters: List<File> = emptyList()

    /** Current page index (always a real physical page, never a spread index). */
    private var currentIndex = 0
    private var mode = MODE_PAGED
    private var twoPage = false
    private var coverSolo = true
    private var readingDir = "ltr"
    private var filter = Filters.NONE
    private var brightness = 100
    private var spacingDp = 0
    private var nightFilter = false
    private var keepOn = false
    private var barsVisible = true
    private var fit = "screen"
    private var autoCrop = false
    private var seriesActive = false

    /** Suppresses premature read-marking and endCard display while the resume/first-page dialog is open. */
    private var awaitingStartChoice = false

    /** True until the reader touches the strip, while the view is being held at the chapter's end. */
    private var alignToBottom = false

    private var openJob: Job? = null
    private var endDismissed = false
    private var markedRead = false

    // auto-scroll
    private val handler = Handler(Looper.getMainLooper())
    private var autoScrolling = false
    private var autoRemainder = 0f
    private var autoLastFrame = 0L
    private var keepAutoScroll = false

    // sleep timer
    private var sleepDeadlineMs = 0L
    private var sleepWarned = false
    private val sleepTick = object : Runnable {
        override fun run() {
            if (sleepDeadlineMs <= 0L) return
            val remaining = sleepDeadlineMs - SystemClock.elapsedRealtime()
            if (remaining <= 0L) {
                sleepDeadlineMs = 0L
                stopAutoScroll()
                saveHistory()
                flushStats()
                toast(R.string.sleep_timer_expired)
                finish()
                return
            }
            if (!sleepWarned && remaining <= 60_000L) {
                sleepWarned = true
                toast(R.string.sleep_timer_one_min)
            }
            sheetSync?.invoke()
            handler.postDelayed(this, 1000L)
        }
    }

    // reading statistics for the current session
    private var sessionStart = 0L
    private val seenPages = BitSet()

    private var cropSource: File? = null

    private val cropLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                result.data?.getStringExtra(CropActivity.EXTRA_RESULT)?.let {
                    toast(getString(R.string.crop_saved, it))
                }
            }
            deleteCropSource()
        }

    private val pagesLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val page = result.data?.getIntExtra(PagesActivity.EXTRA_RESULT_PAGE, -1) ?: -1
                if (page >= 0) goTo(page)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        library = Library(this)
        setContentView(R.layout.activity_viewer)
        bindViews()
        setupWindowInsets()
        loadPrefs()

        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        savedInstanceState?.getString(STATE_PATH)?.let { savedPath ->
            intent.putExtra(EXTRA_PATH, savedPath)
            intent.putExtra(EXTRA_PAGE, savedInstanceState.getInt(STATE_PAGE, -1))
        }

        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) stopAutoScroll()
            }

            override fun onPageSelected(position: Int) {
                if (mode != MODE_PAGED) return
                currentIndex = itemToPage(position)
                seek.progress = position
                onPageChanged()
            }
        })
        vertical.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    alignToBottom = false
                    stopAutoScroll()
                }
            }

            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                if (mode == MODE_PAGED) return
                val lm = rv.layoutManager as? LinearLayoutManager ?: return
                val firstVisible = lm.findFirstVisibleItemPosition()
                val pos = lm.findFirstCompletelyVisibleItemPosition().takeIf { it >= 0 }
                    ?: firstVisible
                // Roll into the appended chapter only once the finished chapter has completely
                // scrolled off the top edge (firstVisible >= chapterPages()) and alignToBottom
                // is not holding the view at the end of a chapter just entered backwards.
                if (!alignToBottom && stripAhead != null && firstVisible >= chapterPages()) {
                    if (!stripRollPending) {
                        stripRollPending = true
                        rv.post {
                            stripRollPending = false
                            commitStripChapter()
                        }
                    }
                    return
                }
                if (stripRollPending) return
                if (pos >= 0 && itemToPage(pos) != currentIndex) {
                    currentIndex = itemToPage(pos)
                        .coerceIn(0, (chapterPages() - 1).coerceAtLeast(0))
                    seek.progress = pos.coerceAtMost(seek.max)
                    onPageChanged()
                }
                if (mode == MODE_LONGSTRIP) updateEndCard()
            }
        })

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (fromUser) {
                    alignToBottom = false
                    goTo(itemToPage(value))
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        (pager.getChildAt(0) as? RecyclerView)?.addOnItemTouchListener(overSwipeListener)
        vertical.addOnItemTouchListener(overSwipeListener)

        btnPrevChapter = findViewById(R.id.btn_prev_chapter)
        btnNextChapter = findViewById(R.id.btn_next_chapter)
        btnPrevChapter.setOnClickListener { openPrevChapter() }
        btnNextChapter.setOnClickListener { openNextChapter() }
        findViewById<View>(R.id.btn_prev_page).setOnClickListener { stepPage(forward = false) }
        findViewById<View>(R.id.btn_next_page).setOnClickListener { stepPage(forward = true) }
        findViewById<View>(R.id.btn_pages).setOnClickListener { openPages() }

        endPrev.setOnClickListener { openPrevChapter() }
        endNext.setOnClickListener { openNextChapter() }
        findViewById<View>(R.id.end_close).setOnClickListener {
            endDismissed = true
            hideEndCard()
        }

        open()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        file?.absolutePath?.let { outState.putString(STATE_PATH, it) }
        outState.putInt(STATE_PAGE, currentIndex)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        alignToBottom = false
        stopAutoScroll()
        flushStats()
        saveHistory()
        openJob?.cancel()
        closeSource()
        endDismissed = false
        hideEndCard()
        open()
    }

    override fun onStart() {
        super.onStart()
        sessionStart = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        updateChopDetector()
        updateHud()
    }

    override fun onPause() {
        chopDetector?.stop()
        super.onPause()
    }

    private fun updateChopDetector() {
        if (!prefs.chopExit) {
            chopDetector?.stop()
            return
        }
        val detector = chopDetector ?: ChopDetector(this, { prefs.chopSensitivity }) { panicExit() }.also { chopDetector = it }
        detector.start()
    }

    private fun panicExit() {
        if (isFinishing) return
        stopAutoScroll()
        saveHistory()
        flushStats()
        finishAndRemoveTask()
    }

    override fun onStop() {
        super.onStop()
        stopAutoScroll()
        saveHistory()
        flushStats()
    }

    override fun onDestroy() {
        super.onDestroy()
        sheetSync = null
        handler.removeCallbacks(sleepTick)
        chopDetector?.stop()
        chopDetector = null
        stopAutoScroll()
        openJob?.cancel()
        closeSource()
        deleteCropSource()
    }

    private fun flushStats() {
        if (sessionStart == 0L) return
        val seconds = ((SystemClock.elapsedRealtime() - sessionStart) / 1000L)
            .coerceIn(0L, MAX_SESSION_SECONDS)
        val pages = seenPages.cardinality()
        sessionStart = SystemClock.elapsedRealtime()
        seenPages.clear()
        if (source != null) library.addReading(seconds, pages)
    }

    private fun deleteCropSource() {
        cropSource?.let { runCatching { it.delete() } }
        cropSource = null
    }

    private fun bindViews() {
        pager = findViewById(R.id.viewer_pager)
        vertical = findViewById(R.id.viewer_vertical)
        toolbar = findViewById(R.id.viewer_toolbar)
        bottomBar = findViewById(R.id.viewer_bottom)
        hudView = findViewById(R.id.viewer_hud)
        seek = findViewById(R.id.viewer_seek)
        pageText = findViewById(R.id.viewer_page_text)
        chapterText = findViewById(R.id.viewer_chapter_text)
        dim = findViewById(R.id.viewer_dim)
        nightOverlay = findViewById(R.id.night_overlay)
        progress = findViewById(R.id.viewer_progress)
        endCard = findViewById(R.id.viewer_end)
        endSubtitle = findViewById(R.id.end_subtitle)
        endNext = findViewById(R.id.end_next)
        endPrev = findViewById(R.id.end_prev)
        vertical.layoutManager = LinearLayoutManager(this)
    }

    /** Ensures toolbar, bottom bar and HUD clear system bars and camera cutouts. */
    private fun setupWindowInsets() {
        val root = findViewById<View>(R.id.viewer_root)
        val density = resources.displayMetrics.density
        val baseMargin = (10 * density).toInt()
        val hudMargin = (12 * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            toolbar.setPadding(insets.left, insets.top, insets.right, 0)
            (bottomBar.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.leftMargin = baseMargin + insets.left
                lp.rightMargin = baseMargin + insets.right
                lp.bottomMargin = baseMargin + insets.bottom
                bottomBar.layoutParams = lp
            }
            (hudView.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                lp.rightMargin = hudMargin + insets.right
                lp.bottomMargin = hudMargin + insets.bottom
                hudView.layoutParams = lp
            }
            windowInsets
        }
    }

    private fun loadPrefs() {
        mode = prefs.pageMode
        twoPage = prefs.twoPage
        coverSolo = prefs.coverSolo
        readingDir = prefs.readingDir
        fit = prefs.fit
        autoCrop = prefs.autoCrop
        filter = prefs.filter
        brightness = prefs.brightness
        spacingDp = prefs.pageSpacing
        keepOn = prefs.keepScreenOn
        nightFilter = prefs.nightFilter
        applyKeepScreenOn()
        applyNight()
        applyBrightness()
        if (prefs.fullscreen) setFullscreen(true)
    }

    /** Applies the folder's remembered layout, if it has one and the feature is on. */
    private fun applySeries(folder: String) {
        val saved = if (prefs.perSeries) library.seriesFor(folder) else null
        seriesActive = saved != null
        if (saved == null) return
        mode = saved.mode.takeIf { it == MODE_PAGED || it == MODE_VERTICAL || it == MODE_LONGSTRIP } ?: MODE_PAGED
        readingDir = if (saved.dir == "rtl") "rtl" else "ltr"
        fit = saved.fit
        twoPage = saved.twoPage
        coverSolo = saved.coverSolo
    }

    private fun persistLayout() {
        val folder = seriesFolderPath()
        if (seriesActive && folder != null) {
            library.setSeries(folder, SeriesSettings(mode, readingDir, fit, twoPage, coverSolo))
        } else {
            prefs.pageMode = mode
            prefs.readingDir = readingDir
            prefs.fit = fit
            prefs.twoPage = twoPage
            prefs.coverSolo = coverSolo
        }
    }

    private fun seriesFolderPath(): String? = file?.parentFile?.absolutePath

    private fun toggleSeries() {
        val folder = seriesFolderPath() ?: return
        seriesActive = !seriesActive
        if (seriesActive) {
            library.setSeries(folder, SeriesSettings(mode, readingDir, fit, twoPage, coverSolo))
            toast(R.string.series_saved)
        } else {
            library.setSeries(folder, null)
            toast(R.string.series_cleared)
        }
        invalidateOptionsMenu()
    }

    // ------------------------------------------------------------------ loading

    private fun open() {
        val path = intent.getStringExtra(EXTRA_PATH)
        if (path == null) {
            finish()
            return
        }
        val rawTarget = File(path)
        val parent = rawTarget.parentFile
        val isLooseImageInChapter = rawTarget.isFile &&
            Formats.typeOf(rawTarget.name) == com.infinity.comicreader.data.EntryType.IMAGE &&
            parent != null && FolderImageSource.isImageChapterFolder(parent)
        val target = if (isLooseImageInChapter && parent != null) parent else rawTarget

        file = target
        setChapterTitle(target)
        progress.visibility = View.VISIBLE
        markedRead = false
        awaitingStartChoice = false
        mode = prefs.pageMode
        readingDir = prefs.readingDir
        fit = prefs.fit
        twoPage = prefs.twoPage
        coverSolo = prefs.coverSolo
        target.parentFile?.absolutePath?.let { applySeries(it) }

        openJob = lifecycleScope.launch {
            val (src, siblings, info, initialImageIndex) = withContext(AppDispatchers.io) {
                val s = runCatching { PageSources.open(target, prefs.charset) }.getOrNull()
                val metadata = runCatching { s?.comicInfo() }.getOrNull()
                val imgIdx = if (isLooseImageInChapter && s is FolderImageSource) s.indexOf(rawTarget) else -1
                try {
                    ensureActive()
                } catch (e: CancellationException) {
                    runCatching { s?.close() }
                    throw e
                }
                Quadruple(s, siblingChapters(target), metadata, imgIdx)
            }
            progress.visibility = View.GONE
            chapters = siblings
            comicInfo = info
            if (src == null) {
                toast(R.string.error_open)
                finish()
                return@launch
            }
            if (src.pageCount <= 0) {
                withContext(AppDispatchers.io) { runCatching { src.close() } }
                toast(R.string.error_open)
                finish()
                return@launch
            }
            if (!seriesActive && info?.isMangaRtl == true && mode == MODE_PAGED) {
                readingDir = "rtl"
            }
            if (!info?.title.isNullOrBlank()) {
                toolbar.subtitle = info?.title
            } else {
                toolbar.subtitle = null
            }
            source = src
            setup(src, initialImageIndex)
        }
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    private fun setChapterTitle(target: File) {
        val label = chapterLabel(target)
        title = label
        supportActionBar?.title = label
        toolbar.title = label
    }

    private fun closeSource() {
        adapter?.release()
        adapter = null
        stripAheadJob?.cancel()
        stripAheadJob = null
        val ahead = stripAhead
        stripAhead = null
        stripAheadFile = null
        val current = source
        source = null
        comicInfo = null
        pager.adapter = null
        vertical.adapter = null
        if (ahead != null || current != null) {
            lifecycleScope.launch(AppDispatchers.io) {
                runCatching { ahead?.close() }
                runCatching { current?.close() }
            }
        }
    }

    private fun siblingChapters(f: File): List<File> {
        val dir = f.parentFile ?: return listOf(f)
        val files = dir.listFiles() ?: return listOf(f)
        val openable = if (f.isDirectory) {
            files.filter { !it.name.startsWith(".") && FolderImageSource.isImageChapterFolder(it) }
                .sortedWith(compareBy(NaturalOrder) { it.name })
        } else {
            files.filter { it.isFile && !it.name.startsWith(".") && Formats.isOpenable(it.name) }
                .sortedWith(compareBy(NaturalOrder) { it.name })
        }
        return if (openable.any { it.absolutePath == f.absolutePath }) openable else openable + f
    }

    private fun setup(src: PageSource, initialImageIndex: Int = -1) {
        val requested = if (initialImageIndex > 0) initialImageIndex else intent.getIntExtra(EXTRA_PAGE, -1)
        val arrivedFromNextChapter = requested == LAST_PAGE
        // Clear LAST_PAGE from intent once read so a later recreation does not snap back to the end.
        if (arrivedFromNextChapter) {
            intent.putExtra(EXTRA_PAGE, (src.pageCount - 1).coerceAtLeast(0))
        }
        // Arriving on the last page by paging back from the next chapter must not pop up the end-of-chapter card.
        endDismissed = arrivedFromNextChapter

        val saved = library.pageFor(file!!.absolutePath)
        val willAsk = requested < 0 && prefs.resume && prefs.askResume &&
            saved in 1 until src.pageCount && !isFinishing && !isDestroyed
        awaitingStartChoice = willAsk

        val start = when {
            requested >= 0 -> requested
            prefs.resume -> saved
            else -> 0
        }
        currentIndex = start.coerceIn(0, src.pageCount - 1)
        seenPages.clear()
        alignToBottom = arrivedFromNextChapter && mode != MODE_PAGED
        attach()
        if (alignToBottom) {
            alignStripBottom(ALIGN_TRIES)
        } else {
            prepareStripAhead()
        }

        if (keepAutoScroll) {
            keepAutoScroll = false
            startAutoScroll()
        }

        if (willAsk) {
            askStartPage(saved, src.pageCount)
        }
    }

    /**
     * Scrolls so the bottom edge of this chapter's last page is at the bottom of the screen.
     * Delays [prepareStripAhead] until alignment finishes so the alignment pass never scrolls
     * into an appended next chapter.
     */
    private fun alignStripBottom(triesLeft: Int) {
        if (!alignToBottom || isFinishing || isDestroyed) {
            prepareStripAhead()
            return
        }
        val count = source?.pageCount ?: return
        val last = pageToItem((count - 1).coerceAtLeast(0))
        val lm = vertical.layoutManager as? LinearLayoutManager ?: return
        val view = lm.findViewByPosition(last)
        if (view == null) {
            vertical.scrollToPosition(last)
        } else {
            val overshoot = view.bottom - (vertical.height - vertical.paddingBottom)
            if (overshoot > 0) vertical.scrollBy(0, overshoot)
        }
        if (triesLeft > 0) {
            vertical.postDelayed({ alignStripBottom(triesLeft - 1) }, ALIGN_RETRY_MS)
        } else {
            alignToBottom = false
            prepareStripAhead()
        }
    }

    private fun askStartPage(saved: Int, count: Int) {
        val percent = ((saved + 1) * 100f / count).toInt().coerceIn(1, 100)
        AlertDialog.Builder(this)
            .setTitle(chapterLabel(file!!))
            .setMessage(getString(R.string.resume_progress, saved + 1, count, percent))
            .setPositiveButton(R.string.resume_page) { _, _ ->
                awaitingStartChoice = false
                goTo(saved)
            }
            .setNegativeButton(R.string.first_page) { _, _ ->
                awaitingStartChoice = false
                goTo(0)
            }
            .setOnCancelListener {
                awaitingStartChoice = false
                onPageChanged()
            }
            .show()
    }

    private fun attach() {
        val src = source ?: return
        pager.adapter = null
        vertical.adapter = null
        adapter?.release()

        val a = PageAdapter(
            source = src,
            mode = adapterMode(),
            onTap = { fraction -> onTap(fraction) },
            doubleTapZoom = prefs.doubleTapZoom,
            twoPage = useSpread(),
            coverSolo = useCoverSolo(),
            rtl = readingDir == "rtl",
            onVerticalDrag = if (brightnessGestureEnabled()) ({ dy -> onBrightnessDrag(dy) }) else null,
            autoCrop = autoCrop,
            preload = prefs.preload
        )
        a.fitMode = effectiveFit()
        a.filter = filter
        a.spacingPx = spacingPx()
        adapter = a

        val item = pageToItem(currentIndex)
        if (mode != MODE_PAGED && !alignToBottom) stripAhead?.let { a.append(it) }
        val isRtlPaged = readingDir == "rtl" && mode == MODE_PAGED
        seek.layoutDirection = if (isRtlPaged) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        if (mode == MODE_PAGED) {
            pager.layoutDirection = if (isRtlPaged) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
            vertical.visibility = View.GONE
            pager.visibility = View.VISIBLE
            pager.adapter = a
            pager.setCurrentItem(item, false)
        } else {
            pager.visibility = View.GONE
            vertical.visibility = View.VISIBLE
            vertical.adapter = a
            vertical.scrollToPosition(item)
        }
        seek.max = pageToItem((src.pageCount - 1).coerceAtLeast(0))
        seek.progress = item
        onPageChanged()
    }

    // ------------------------------------------------------- seamless chapter roll

    private fun chapterPages() = source?.pageCount ?: 0

    private fun prepareStripAhead() {
        stripAheadJob?.cancel()
        stripAheadJob = null
        if (mode == MODE_PAGED || !prefs.autoNext || alignToBottom) return
        if (stripAhead != null) return
        val next = nextChapter() ?: return
        stripAheadJob = lifecycleScope.launch {
            var opened: PageSource? = null
            var adopted = false
            try {
                withContext(AppDispatchers.io) {
                    opened = runCatching { PageSources.open(next, prefs.charset) }.getOrNull()
                }
                val src = opened
                if (src == null || src.pageCount <= 0) return@launch
                ensureActive()
                stripAhead = src
                stripAheadFile = next
                adopted = true
                if (mode != MODE_PAGED && prefs.autoNext && !alignToBottom) adapter?.append(src)
            } finally {
                if (!adopted) {
                    val toClose = opened
                    if (toClose != null) {
                        lifecycleScope.launch(AppDispatchers.io) { runCatching { toClose.close() } }
                    }
                }
            }
        }
    }

    private fun commitStripChapter() {
        val a = adapter ?: return
        val finished = source ?: return
        val next = stripAhead ?: return
        val nextFile = stripAheadFile ?: return
        val boundary = finished.pageCount

        currentIndex = (boundary - 1).coerceAtLeast(0)
        onPageChanged()
        saveHistory()
        flushStats()

        val lm = vertical.layoutManager as? LinearLayoutManager
        val first = lm?.findFirstVisibleItemPosition() ?: boundary
        val anchor = first.coerceAtLeast(boundary)
        val top = lm?.findViewByPosition(anchor)?.top ?: 0

        source = next
        file = nextFile
        currentIndex = (anchor - boundary).coerceAtLeast(0)
        intent.putExtra(EXTRA_PATH, nextFile.absolutePath)
        intent.putExtra(EXTRA_PAGE, currentIndex)
        stripAhead = null
        stripAheadFile = null
        seenPages.clear()
        markedRead = false
        endDismissed = false
        setChapterTitle(nextFile)

        a.dropItemsBefore(boundary)
        lm?.scrollToPositionWithOffset((anchor - boundary).coerceAtLeast(0), top)

        seek.max = pageToItem((next.pageCount - 1).coerceAtLeast(0))
        seek.progress = pageToItem(currentIndex)
        onPageChanged()
        prepareStripAhead()
    }

    private fun dropStripAhead() {
        stripAheadJob?.cancel()
        stripAheadJob = null
        val ahead = stripAhead ?: return
        stripAhead = null
        stripAheadFile = null
        val a = adapter
        if (a != null) {
            a.dropAppended()
        } else {
            lifecycleScope.launch(AppDispatchers.io) { runCatching { ahead.close() } }
        }
        updateEndCard()
    }

    private fun adapterMode(): PageAdapter.Mode = when (mode) {
        MODE_VERTICAL -> PageAdapter.Mode.VERTICAL
        MODE_LONGSTRIP -> PageAdapter.Mode.LONG_STRIP
        else -> PageAdapter.Mode.PAGED
    }

    private fun useSpread() = twoPage && mode == MODE_PAGED

    private fun useCoverSolo() = useSpread() && coverSolo

    private fun pageToItem(page: Int) =
        if (useSpread()) SpreadLayout.itemForPage(page, useCoverSolo()) else page

    private fun itemToPage(item: Int) =
        if (useSpread()) SpreadLayout.firstPageForProgress(item, useCoverSolo()) else item

    private fun brightnessGestureEnabled() = prefs.brightnessGesture && mode == MODE_PAGED

    private fun spacingPx(): Int =
        (spacingDp * resources.displayMetrics.density).toInt()

    private fun effectiveFit(): ZoomImageView.FitMode {
        val name = if (mode != MODE_PAGED && fit == "screen") "width" else fit
        return when (name) {
            "width" -> ZoomImageView.FitMode.WIDTH
            "height" -> ZoomImageView.FitMode.HEIGHT
            "stretch" -> ZoomImageView.FitMode.STRETCH
            else -> ZoomImageView.FitMode.SCREEN
        }
    }

    // ------------------------------------------------------------------ paging

    /** Moves one page (or one spread when in spread mode) forward or backward. */
    private fun stepPage(forward: Boolean) {
        alignToBottom = false
        val target = if (useSpread()) {
            SpreadLayout.nextSpreadPage(currentIndex, forward, useCoverSolo())
        } else {
            currentIndex + if (forward) 1 else -1
        }
        goTo(target)
    }

    private fun goTo(page: Int) {
        val count = source?.pageCount ?: return
        if (page < 0) {
            if (prefs.autoNext) openPrevChapter(atEnd = true) else goTo(0)
            return
        }
        if (page >= count) {
            handleAdvance()
            return
        }
        currentIndex = page
        val item = pageToItem(currentIndex)
        if (mode == MODE_PAGED) pager.setCurrentItem(item, false) else vertical.scrollToPosition(item)
        seek.progress = item
        onPageChanged()
    }

    private fun handleAdvance() {
        val next = nextChapter()
        if (next == null) {
            toast(R.string.finished_last_chapter)
            return
        }
        if (!prefs.autoNext) {
            showEndCard()
            return
        }
        if (mode != MODE_PAGED && stripAhead != null) {
            commitStripChapter()
            vertical.scrollToPosition(0)
        } else {
            openChapter(next, 0)
        }
    }

    private fun onTap(fraction: Float) {
        if (!prefs.tapZones || mode == MODE_LONGSTRIP) {
            toggleBars()
            return
        }
        val rtl = readingDir == "rtl" && mode == MODE_PAGED
        val forward = when {
            fraction < 0.33f -> rtl
            fraction > 0.67f -> !rtl
            else -> {
                toggleBars()
                return
            }
        }
        stepPage(forward)
    }

    private fun onPageChanged() {
        val count = source?.pageCount ?: 0
        pageText.text = getString(R.string.page_indicator, currentIndex + 1, count)
        chapterText.text = modeLabel()
        updateChapterButtons()
        updateHud()
        if (awaitingStartChoice) return

        updateEndCard()
        if (currentIndex in 0 until count) seenPages.set(currentIndex)
        val lastItemIndex = pageToItem((count - 1).coerceAtLeast(0))
        val currentItemIndex = pageToItem(currentIndex)
        if (!markedRead && count > 0 && currentItemIndex >= lastItemIndex) {
            markedRead = true
            file?.absolutePath?.let { library.setRead(it, true) }
        }
        invalidateOptionsMenu()
    }

    private fun updateHud() {
        val show = !barsVisible && prefs.readerHud && source != null
        hudView.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        val count = source?.pageCount ?: 0
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val battery = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
        val clock = runCatching { LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrDefault("")
        val parts = ArrayList<String>(3)
        parts += "${currentIndex + 1} / $count"
        if (battery != null) parts += "$battery%"
        if (clock.isNotEmpty()) parts += clock
        hudView.text = parts.joinToString(" · ")
    }

    private fun modeLabel(): String = when (mode) {
        MODE_VERTICAL -> getString(R.string.page_mode_vertical)
        MODE_LONGSTRIP -> getString(R.string.page_mode_longstrip)
        else -> getString(R.string.page_mode_paged)
    }

    private fun updateEndCard() {
        if (awaitingStartChoice) return
        val src = source ?: return
        if (autoAdvance()) {
            hideEndCard()
            return
        }
        val atEnd = when (mode) {
            MODE_LONGSTRIP -> !vertical.canScrollVertically(1)
            else -> pageToItem(currentIndex) >= pageToItem((src.pageCount - 1).coerceAtLeast(0))
        }
        if (!atEnd) {
            endDismissed = false
            hideEndCard()
        } else if (!endDismissed) {
            showEndCard()
        }
    }

    private fun showEndCard() {
        val f = file ?: return
        if (autoAdvance() || awaitingStartChoice) return
        endCard.visibility = View.VISIBLE
        endSubtitle.text = getString(R.string.chapter_end_msg, chapterLabel(f))
        endNext.visibility = if (nextChapter() != null) View.VISIBLE else View.GONE
        endPrev.visibility = if (prevChapter() != null) View.VISIBLE else View.GONE
    }

    private fun hideEndCard() {
        endCard.visibility = View.GONE
    }

    private fun updateChapterButtons() {
        val hasNext = nextChapter() != null
        val hasPrev = prevChapter() != null
        btnNextChapter.isEnabled = hasNext
        btnNextChapter.alpha = if (hasNext) 1f else 0.3f
        btnPrevChapter.isEnabled = hasPrev
        btnPrevChapter.alpha = if (hasPrev) 1f else 0.3f
    }

    private fun saveHistory() {
        val f = file ?: return
        val count = source?.pageCount ?: return
        library.addHistory(
            HistoryItem(f.absolutePath, f.name, currentIndex, count, System.currentTimeMillis())
        )
    }

    // ------------------------------------------------------------------ chapters

    private fun siblingIndex(): Int = chapters.indexOfFirst { it.absolutePath == file?.absolutePath }

    private fun nextChapter(): File? {
        val i = siblingIndex()
        return if (i >= 0 && i + 1 < chapters.size) chapters[i + 1] else null
    }

    private fun prevChapter(): File? {
        val i = siblingIndex()
        return if (i > 0) chapters[i - 1] else null
    }

    private fun openNextChapter() {
        val next = nextChapter()
        if (next == null) {
            toast(R.string.no_next_chapter)
            return
        }
        openChapter(next, 0)
    }

    /**
     * Opens the previous chapter. [atEnd] is for paging backwards past the first page, where
     * the reader expects to land on the previous chapter's last page, not its first.
     */
    private fun openPrevChapter(atEnd: Boolean = false) {
        val prev = prevChapter()
        if (prev == null) {
            toast(R.string.no_prev_chapter)
            return
        }
        openChapter(prev, if (atEnd) LAST_PAGE else 0)
    }

    private fun openChapter(target: File, page: Int) {
        saveHistory()
        val intent = Intent(this, ViewerActivity::class.java)
            .putExtra(EXTRA_PATH, target.absolutePath)
            .putExtra(EXTRA_PAGE, page)
        startActivity(intent)
    }

    private fun chapterLabel(f: File): String {
        if (f.isDirectory) return f.name
        val name = f.name
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    // ------------------------------------------------------------------ gestures

    private fun onBrightnessDrag(dy: Float) {
        val range = 420f * resources.displayMetrics.density
        val delta = (dy / range) * 85f
        setBrightness((brightness + delta).toInt().coerceIn(20, 100))
    }

    private fun setBrightness(value: Int) {
        brightness = value.coerceIn(20, 100)
        prefs.brightness = brightness
        applyBrightness()
    }

    private fun applyBrightness() {
        dim.alpha = ((100 - brightness) / 100f) * 0.82f
    }

    private fun applyNight() {
        nightOverlay.visibility = if (nightFilter) View.VISIBLE else View.GONE
    }

    private fun setNight(enabled: Boolean) {
        if (nightFilter == enabled) return
        nightFilter = enabled
        prefs.nightFilter = enabled
        applyNight()
        invalidateOptionsMenu()
    }

    private fun toggleNight() = setNight(!nightFilter)

    private fun applyKeepScreenOn() {
        if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun setKeepOn(enabled: Boolean) {
        if (keepOn == enabled) return
        keepOn = enabled
        prefs.keepScreenOn = enabled
        applyKeepScreenOn()
        invalidateOptionsMenu()
    }

    private fun toggleKeepOn() = setKeepOn(!keepOn)

    private fun setFullscreen(enabled: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        prefs.fullscreen = enabled
        updateHud()
        invalidateOptionsMenu()
    }

    private fun setReaderHud(enabled: Boolean) {
        prefs.readerHud = enabled
        updateHud()
        invalidateOptionsMenu()
    }

    private fun toggleBars() {
        barsVisible = !barsVisible
        animateBar(toolbar, barsVisible)
        animateBar(bottomBar, barsVisible)
        updateHud()
    }

    private fun animateBar(view: View, visible: Boolean) {
        view.animate().cancel()
        if (visible) {
            view.visibility = View.VISIBLE
            view.animate().alpha(1f).setDuration(150).start()
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction {
                view.visibility = View.GONE
            }.start()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isVolumeKey(keyCode)) {
            if (event.repeatCount == 0) {
                alignToBottom = false
                stopAutoScroll()
                val volumeDown = keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                val forward = if (prefs.invertVolumeKeys) !volumeDown else volumeDown
                if (mode == MODE_LONGSTRIP) {
                    val distance = (vertical.height * 0.75f).toInt().coerceAtLeast(200)
                    val dy = if (forward) distance else -distance
                    if (forward && !vertical.canScrollVertically(1)) {
                        handleAdvance()
                    } else {
                        vertical.smoothScrollBy(0, dy)
                    }
                } else {
                    stepPage(forward)
                }
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (prefs.volumeKeys && isVolumeKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun isVolumeKey(keyCode: Int) =
        keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN

    // ------------------------------------------------------------------ menu

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_viewer, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_bookmark)?.setIcon(
            if (file?.let { library.isBookmarked(it.absolutePath, currentIndex) } == true)
                R.drawable.ic_bookmark else R.drawable.ic_bookmark_border
        )
        menu.findItem(R.id.menu_keep_on)?.isChecked = keepOn
        menu.findItem(R.id.menu_night)?.isChecked = nightFilter
        menu.findItem(R.id.menu_fullscreen)?.isChecked = prefs.fullscreen
        menu.findItem(R.id.menu_reader_hud)?.isChecked = prefs.readerHud
        menu.findItem(R.id.menu_two_page)?.apply {
            isChecked = twoPage
            isEnabled = mode == MODE_PAGED
        }
        menu.findItem(R.id.menu_cover_solo)?.apply {
            isChecked = coverSolo
            isEnabled = mode == MODE_PAGED && twoPage
        }
        checkOnly(menu, if (readingDir == "rtl") R.id.menu_dir_rtl else R.id.menu_dir_ltr)
        checkOnly(
            menu,
            when (mode) {
                MODE_VERTICAL -> R.id.menu_mode_vertical
                MODE_LONGSTRIP -> R.id.menu_mode_longstrip
                else -> R.id.menu_mode_paged
            }
        )
        checkOnly(
            menu,
            when (effectiveFit()) {
                ZoomImageView.FitMode.WIDTH -> R.id.menu_fit_width
                ZoomImageView.FitMode.HEIGHT -> R.id.menu_fit_height
                ZoomImageView.FitMode.STRETCH -> R.id.menu_fit_stretch
                else -> R.id.menu_fit_screen
            }
        )
        checkOnly(
            menu,
            when (filter) {
                Filters.INVERT -> R.id.menu_filter_invert
                Filters.GRAYSCALE -> R.id.menu_filter_grayscale
                Filters.CONTRAST -> R.id.menu_filter_contrast
                Filters.SEPIA -> R.id.menu_filter_sepia
                else -> R.id.menu_filter_none
            }
        )
        checkOnly(
            menu,
            when (requestedOrientation) {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> R.id.menu_orient_portrait
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> R.id.menu_orient_landscape
                else -> R.id.menu_orient_default
            }
        )
        menu.findItem(R.id.menu_auto_scroll)?.isChecked = autoScrolling
        menu.findItem(R.id.menu_auto_next)?.isChecked = prefs.autoNext
        menu.findItem(R.id.menu_auto_crop)?.apply {
            isChecked = autoCrop
            isEnabled = true
        }
        menu.findItem(R.id.menu_series)?.apply {
            isVisible = prefs.perSeries
            isChecked = seriesActive
        }
        menu.findItem(R.id.menu_sleep_timer)?.title = sleepTimerButtonLabel()
        menu.findItem(R.id.menu_chapters)?.isEnabled = chapters.size > 1
        menu.findItem(R.id.menu_prev_chapter)?.isEnabled = prevChapter() != null
        menu.findItem(R.id.menu_next_chapter)?.isEnabled = nextChapter() != null
        return super.onPrepareOptionsMenu(menu)
    }

    private fun checkOnly(menu: Menu, id: Int) {
        menu.findItem(id)?.isChecked = true
    }

    private val valueMenus: Map<Int, () -> Unit> by lazy {
        mapOf(
            R.id.menu_fit_screen to { setFit("screen") },
            R.id.menu_fit_width to { setFit("width") },
            R.id.menu_fit_height to { setFit("height") },
            R.id.menu_fit_stretch to { setFit("stretch") },
            R.id.menu_filter_none to { setFilter(Filters.NONE) },
            R.id.menu_filter_invert to { setFilter(Filters.INVERT) },
            R.id.menu_filter_grayscale to { setFilter(Filters.GRAYSCALE) },
            R.id.menu_filter_contrast to { setFilter(Filters.CONTRAST) },
            R.id.menu_filter_sepia to { setFilter(Filters.SEPIA) },
            R.id.menu_mode_paged to { setPageMode(MODE_PAGED) },
            R.id.menu_mode_vertical to { setPageMode(MODE_VERTICAL) },
            R.id.menu_mode_longstrip to { setPageMode(MODE_LONGSTRIP) },
            R.id.menu_dir_ltr to { setReadingDir("ltr") },
            R.id.menu_dir_rtl to { setReadingDir("rtl") },
            R.id.menu_orient_default to { setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) },
            R.id.menu_orient_portrait to { setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) },
            R.id.menu_orient_landscape to { setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) }
        )
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val id = item.itemId
        val assign = valueMenus[id]
        if (assign != null) {
            assign()
            return true
        }
        return when (id) {
            // Submenus open in place of the main list; "Back" brings the main list back.
            R.id.menu_submenu_back_display,
            R.id.menu_submenu_back_filters,
            R.id.menu_submenu_back_reading,
            R.id.menu_submenu_back_page_tools -> { toolbar.post { toolbar.showOverflowMenu() }; true }
            R.id.menu_tune -> { showReaderSheet(); true }
            R.id.menu_chapters -> { showChapterList(); true }
            R.id.menu_comic_info -> { showComicInfoDialog(); true }
            R.id.menu_sleep_timer -> { showSleepTimerDialog(); true }
            R.id.menu_auto_scroll -> { toggleAutoScroll(); true }
            R.id.menu_auto_next -> { setAutoNext(!prefs.autoNext); true }
            R.id.menu_auto_speed -> { showAutoSpeedDialog(); true }
            R.id.menu_auto_crop -> { toggleAutoCrop(); true }
            R.id.menu_series -> { toggleSeries(); true }
            R.id.menu_share_page -> { exportPage(share = true); true }
            R.id.menu_save_page -> { exportPage(share = false); true }
            R.id.menu_jump -> { showJumpDialog(); true }
            R.id.menu_rotate -> { cycleOrientation(); true }
            R.id.menu_bookmark -> { toggleBookmark(); true }
            R.id.menu_bookmark_list -> { showBookmarks(); true }
            R.id.menu_pages -> { openPages(); true }
            R.id.menu_crop -> { startCrop(); true }
            R.id.menu_two_page -> { toggleTwoPage(); true }
            R.id.menu_cover_solo -> { toggleCoverSolo(); true }
            R.id.menu_brightness -> { showBrightnessDialog(); true }
            R.id.menu_page_spacing -> { showSpacingDialog(); true }
            R.id.menu_prev_chapter -> { openPrevChapter(); true }
            R.id.menu_next_chapter -> { openNextChapter(); true }
            R.id.menu_keep_on -> { toggleKeepOn(); true }
            R.id.menu_night -> { toggleNight(); true }
            R.id.menu_fullscreen -> { setFullscreen(!prefs.fullscreen); true }
            R.id.menu_reader_hud -> { setReaderHud(!prefs.readerHud); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ------------------------------------------------------------------ reader options

    private fun setFit(name: String) {
        fit = name
        persistLayout()
        adapter?.fitMode = effectiveFit()
        invalidateOptionsMenu()
    }

    private fun setFilter(name: String) {
        filter = name
        prefs.filter = name
        adapter?.filter = name
        invalidateOptionsMenu()
    }

    private fun setReadingDir(dir: String) {
        if (readingDir == dir) return
        readingDir = dir
        persistLayout()
        attach()
    }

    private fun setPageMode(newMode: String) {
        if (newMode == mode) return
        alignToBottom = false
        stopAutoScroll()
        mode = newMode
        if (mode == MODE_PAGED) dropStripAhead()
        persistLayout()
        attach()
        prepareStripAhead()
    }

    private fun setSpacingDp(dp: Int) {
        spacingDp = dp.coerceIn(0, 48)
        prefs.pageSpacing = spacingDp
        adapter?.spacingPx = spacingPx()
    }

    private fun toggleTwoPage() {
        if (mode != MODE_PAGED) {
            toast(R.string.menu_spread)
            return
        }
        twoPage = !twoPage
        persistLayout()
        attach()
    }

    private fun toggleCoverSolo() {
        if (mode != MODE_PAGED || !twoPage) return
        coverSolo = !coverSolo
        persistLayout()
        attach()
    }

    private fun setOrientationLock(value: Int) {
        requestedOrientation = value
        invalidateOptionsMenu()
    }

    private fun cycleOrientation() {
        val landscape = resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        setOrientationLock(
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        )
    }

    // ------------------------------------------------------------------ reader bottom sheet

    private var sheetSync: (() -> Unit)? = null
    private var sheetSyncing = false
    private var chopDetector: ChopDetector? = null

    override fun invalidateOptionsMenu() {
        super.invalidateOptionsMenu()
        sheetSync?.invoke()
    }

    private fun showReaderSheet() {
        val view = layoutInflater.inflate(R.layout.sheet_reader, null)
        val dialog = BottomSheetDialog(this)
        dialog.setContentView(view)
        dialog.behavior.skipCollapsed = true
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED

        val syncers = ArrayList<() -> Unit>()

        bindModeSection(view, syncers)
        bindFitSection(view, syncers)
        bindFilterSection(view, syncers)
        bindDirectionSection(view, syncers)
        bindBrightnessSection(view)
        bindSpacingSection(view)
        bindAutoScrollSection(view, dialog, syncers)
        bindOrientationSection(view, syncers)
        bindSwitchSection(view, syncers)
        bindActionSection(view, dialog, syncers)

        view.findViewById<View>(R.id.sheet_more_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        sheetSync = {
            sheetSyncing = true
            try {
                syncers.forEach { it() }
            } finally {
                sheetSyncing = false
            }
        }
        sheetSync?.invoke()
        dialog.setOnDismissListener { sheetSync = null }
        dialog.show()
    }

    private fun bindModeSection(view: View, syncers: MutableList<() -> Unit>) {
        val group = view.findViewById<MaterialButtonToggleGroup>(R.id.sheet_page_mode)
        syncers += {
            group.check(
                when (mode) {
                    MODE_VERTICAL -> R.id.btn_mode_vertical
                    MODE_LONGSTRIP -> R.id.btn_mode_longstrip
                    else -> R.id.btn_mode_paged
                }
            )
        }
        group.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || sheetSyncing) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btn_mode_vertical -> setPageMode(MODE_VERTICAL)
                R.id.btn_mode_longstrip -> setPageMode(MODE_LONGSTRIP)
                R.id.btn_mode_paged -> setPageMode(MODE_PAGED)
            }
        }
    }

    private fun bindFitSection(view: View, syncers: MutableList<() -> Unit>) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_fit)
        syncers += {
            group.check(
                when (effectiveFit()) {
                    ZoomImageView.FitMode.WIDTH -> R.id.chip_fit_width
                    ZoomImageView.FitMode.HEIGHT -> R.id.chip_fit_height
                    ZoomImageView.FitMode.STRETCH -> R.id.chip_fit_stretch
                    else -> R.id.chip_fit_screen
                }
            )
            for (i in 0 until group.childCount) group.getChildAt(i).isEnabled = mode != MODE_LONGSTRIP
        }
        group.setOnCheckedStateChangeListener { _, checked ->
            if (sheetSyncing) return@setOnCheckedStateChangeListener
            when (checked.firstOrNull()) {
                R.id.chip_fit_width -> setFit("width")
                R.id.chip_fit_height -> setFit("height")
                R.id.chip_fit_stretch -> setFit("stretch")
                R.id.chip_fit_screen -> setFit("screen")
            }
        }
    }

    private fun bindFilterSection(view: View, syncers: MutableList<() -> Unit>) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_filter)
        syncers += {
            group.check(
                when (filter) {
                    Filters.INVERT -> R.id.chip_filter_invert
                    Filters.GRAYSCALE -> R.id.chip_filter_grayscale
                    Filters.CONTRAST -> R.id.chip_filter_contrast
                    Filters.SEPIA -> R.id.chip_filter_sepia
                    else -> R.id.chip_filter_none
                }
            )
        }
        group.setOnCheckedStateChangeListener { _, checked ->
            if (sheetSyncing) return@setOnCheckedStateChangeListener
            when (checked.firstOrNull()) {
                R.id.chip_filter_invert -> setFilter(Filters.INVERT)
                R.id.chip_filter_grayscale -> setFilter(Filters.GRAYSCALE)
                R.id.chip_filter_contrast -> setFilter(Filters.CONTRAST)
                R.id.chip_filter_sepia -> setFilter(Filters.SEPIA)
                R.id.chip_filter_none -> setFilter(Filters.NONE)
            }
        }
    }

    private fun bindDirectionSection(view: View, syncers: MutableList<() -> Unit>) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_dir)
        syncers += { group.check(if (readingDir == "rtl") R.id.chip_dir_rtl else R.id.chip_dir_ltr) }
        group.setOnCheckedStateChangeListener { _, checked ->
            if (sheetSyncing) return@setOnCheckedStateChangeListener
            when (checked.firstOrNull()) {
                R.id.chip_dir_rtl -> setReadingDir("rtl")
                R.id.chip_dir_ltr -> setReadingDir("ltr")
            }
        }
    }

    private fun bindBrightnessSection(view: View) {
        val label = view.findViewById<TextView>(R.id.sheet_brightness_value)
        val slider = view.findViewById<Slider>(R.id.sheet_brightness)
        label.text = brightnessLabel()
        slider.safeSet(brightness.toFloat())
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setBrightness(value.toInt())
            label.text = brightnessLabel()
        }
    }

    private fun brightnessLabel(): String =
        getString(R.string.menu_brightness) + " · " + getString(R.string.percent_format, brightness)

    private fun bindSpacingSection(view: View) {
        val label = view.findViewById<TextView>(R.id.sheet_spacing_value)
        val slider = view.findViewById<Slider>(R.id.sheet_spacing)
        label.text = spacingLabel()
        slider.safeSet(spacingDp.toFloat())
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            setSpacingDp(value.toInt())
            label.text = spacingLabel()
        }
    }

    private fun spacingLabel(): String =
        getString(R.string.menu_page_spacing) + " · $spacingDp dp"

    private fun bindAutoScrollSection(view: View, dialog: BottomSheetDialog, syncers: MutableList<() -> Unit>) {
        val toggle = view.findViewById<MaterialSwitch>(R.id.sheet_auto_scroll)
        val label = view.findViewById<TextView>(R.id.sheet_auto_speed_value)
        val slider = view.findViewById<Slider>(R.id.sheet_auto_speed)

        syncers += {
            toggle.isChecked = autoScrolling
            toggle.isEnabled = source != null
            label.text = getString(R.string.auto_speed_value, prefs.autoSpeed)
            slider.safeSet(prefs.autoSpeed.toFloat())
        }
        toggle.setOnCheckedChangeListener { _, checked ->
            if (checked == autoScrolling) return@setOnCheckedChangeListener
            toggleAutoScroll()
            if (autoScrolling) dialog.dismiss()
        }
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || sheetSyncing) return@addOnChangeListener
            prefs.autoSpeed = value.toInt()
            label.text = getString(R.string.auto_speed_value, prefs.autoSpeed)
        }
    }

    private fun bindSwitchSection(view: View, syncers: MutableList<() -> Unit>) {
        fun bind(id: Int, current: () -> Boolean, enabled: () -> Boolean, onToggle: (Boolean) -> Unit) {
            val sw = view.findViewById<MaterialSwitch>(id) ?: return
            syncers += {
                sw.isChecked = current()
                sw.isEnabled = enabled()
            }
            sw.setOnCheckedChangeListener { _, checked ->
                if (sheetSyncing || checked == current()) return@setOnCheckedChangeListener
                onToggle(checked)
                sheetSync?.invoke()
            }
        }

        bind(R.id.sheet_two_page, { twoPage }, { mode == MODE_PAGED }) { toggleTwoPage() }
        bind(R.id.sheet_cover_solo, { coverSolo }, { mode == MODE_PAGED && twoPage }) { toggleCoverSolo() }
        bind(R.id.sheet_auto_crop, { autoCrop }, { true }) { toggleAutoCrop() }
        bind(
            R.id.sheet_series,
            { seriesActive },
            { prefs.perSeries && file?.parentFile != null }
        ) { toggleSeries() }
        bind(R.id.sheet_night, { nightFilter }, { true }) { setNight(it) }
        bind(R.id.sheet_keep_on, { keepOn }, { true }) { setKeepOn(it) }
        bind(R.id.sheet_fullscreen, { prefs.fullscreen }, { true }) { setFullscreen(it) }
        bind(R.id.sheet_reader_hud, { prefs.readerHud }, { true }) { setReaderHud(it) }
        bind(R.id.sheet_auto_next, { prefs.autoNext }, { true }) { setAutoNext(it) }
        bind(R.id.sheet_tap_zones, { prefs.tapZones }, { true }) { prefs.tapZones = it }
        bind(R.id.sheet_volume_keys, { prefs.volumeKeys }, { true }) { prefs.volumeKeys = it }
        bind(R.id.sheet_invert_volume, { prefs.invertVolumeKeys }, { prefs.volumeKeys }) { prefs.invertVolumeKeys = it }
        bind(R.id.sheet_chop_exit, { prefs.chopExit }, { true }) {
            prefs.chopExit = it
            updateChopDetector()
        }
        val chopLabel = view.findViewById<TextView>(R.id.sheet_chop_sensitivity_value)
        val chopSlider = view.findViewById<Slider>(R.id.sheet_chop_sensitivity)
        syncers += {
            chopLabel.text = getString(R.string.pref_chop_sensitivity) + " · " +
                getString(R.string.auto_speed_short, prefs.chopSensitivity)
            chopSlider.safeSet(prefs.chopSensitivity.toFloat())
            chopSlider.isEnabled = prefs.chopExit
        }
        chopSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.chopSensitivity = value.toInt()
            chopLabel.text = getString(R.string.pref_chop_sensitivity) + " · " +
                getString(R.string.auto_speed_short, prefs.chopSensitivity)
        }
        bind(R.id.sheet_double_tap, { prefs.doubleTapZoom }, { true }) {
            prefs.doubleTapZoom = it
            attach()
        }
    }

    private fun bindActionSection(view: View, dialog: BottomSheetDialog, syncers: MutableList<() -> Unit>) {
        val chapterButton = view.findViewById<View>(R.id.sheet_chapters)
        val shareButton = view.findViewById<View>(R.id.sheet_share)
        val saveButton = view.findViewById<View>(R.id.sheet_save)
        val infoButton = view.findViewById<View>(R.id.sheet_comic_info)
        val sleepButton = view.findViewById<MaterialButton>(R.id.sheet_sleep_timer)
        syncers += {
            chapterButton.isEnabled = chapters.size > 1
            shareButton.isEnabled = source != null
            saveButton.isEnabled = source != null
            infoButton?.isEnabled = source != null
            sleepButton?.text = sleepTimerButtonLabel()
        }
        chapterButton.setOnClickListener { dialog.dismiss(); showChapterList() }
        shareButton.setOnClickListener { dialog.dismiss(); exportPage(share = true) }
        saveButton.setOnClickListener { dialog.dismiss(); exportPage(share = false) }
        infoButton?.setOnClickListener { dialog.dismiss(); showComicInfoDialog() }
        sleepButton?.setOnClickListener { showSleepTimerDialog() }
    }

    private fun bindOrientationSection(view: View, syncers: MutableList<() -> Unit>) {
        val group = view.findViewById<ChipGroup>(R.id.sheet_orientation)
        syncers += {
            group.check(
                when (requestedOrientation) {
                    ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> R.id.chip_orient_portrait
                    ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> R.id.chip_orient_landscape
                    else -> R.id.chip_orient_default
                }
            )
        }
        group.setOnCheckedStateChangeListener { _, checked ->
            if (sheetSyncing) return@setOnCheckedStateChangeListener
            when (checked.firstOrNull()) {
                R.id.chip_orient_portrait ->
                    setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                R.id.chip_orient_landscape ->
                    setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)
                R.id.chip_orient_default ->
                    setOrientationLock(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            }
        }
    }

    private fun Slider.safeSet(value: Float) {
        val step = stepSize
        val snapped = if (step > 0f) valueFrom + Math.round((value - valueFrom) / step) * step else value
        this.value = snapped.coerceIn(valueFrom, valueTo)
    }

    private fun showBrightnessDialog() {
        val slider = Slider(this).apply {
            valueFrom = 20f
            valueTo = 100f
            stepSize = 5f
            value = (brightness / 5 * 5).toFloat().coerceIn(20f, 100f)
        }
        slider.addOnChangeListener { _, value, _ -> setBrightness(value.toInt()) }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_brightness)
            .setView(sliderContainer(slider))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun showSpacingDialog() {
        val slider = Slider(this).apply {
            valueFrom = 0f
            valueTo = 48f
            stepSize = 4f
            value = (spacingDp / 4 * 4).toFloat().coerceIn(0f, 48f)
        }
        slider.addOnChangeListener { _, value, _ -> setSpacingDp(value.toInt()) }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_page_spacing)
            .setView(sliderContainer(slider))
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ------------------------------------------------------------------ sleep timer & comic info

    private fun sleepTimerButtonLabel(): String {
        if (sleepDeadlineMs <= 0L) return getString(R.string.menu_sleep_timer)
        val mins = ((sleepDeadlineMs - SystemClock.elapsedRealtime() + 59_999L) / 60_000L).coerceAtLeast(1L)
        return getString(R.string.sleep_timer_active, mins.toInt())
    }

    private fun showSleepTimerDialog() {
        val minutesOptions = intArrayOf(0, 15, 30, 45, 60)
        val labels = minutesOptions.map { mins ->
            if (mins == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.sleep_timer_minutes, mins)
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.menu_sleep_timer)
            .setItems(labels) { _, which ->
                val mins = minutesOptions[which]
                handler.removeCallbacks(sleepTick)
                if (mins <= 0) {
                    sleepDeadlineMs = 0L
                    sleepWarned = false
                    toast(R.string.sleep_timer_cancelled)
                } else {
                    sleepDeadlineMs = SystemClock.elapsedRealtime() + mins * 60_000L
                    sleepWarned = false
                    handler.postDelayed(sleepTick, 1000L)
                    toast(getString(R.string.sleep_timer_set, mins))
                }
                invalidateOptionsMenu()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showComicInfoDialog() {
        val f = file ?: return
        val src = source ?: return
        val pageIdx = currentIndex
        lifecycleScope.launch {
            val dims = withContext(AppDispatchers.io) {
                runCatching { src.pageSize(pageIdx) }.getOrNull()
            }
            val info = comicInfo
            val text = buildString {
                if (info != null && !info.isEmpty) {
                    if (info.title.isNotBlank()) appendLine("${getString(R.string.info_title)}: ${info.title}")
                    if (info.series.isNotBlank()) {
                        val num = if (info.number.isNotBlank()) " #${info.number}" else ""
                        appendLine("${getString(R.string.info_series)}: ${info.series}$num")
                    }
                    if (info.writer.isNotBlank()) appendLine("${getString(R.string.info_writer)}: ${info.writer}")
                    if (info.penciller.isNotBlank()) appendLine("${getString(R.string.info_artist)}: ${info.penciller}")
                    if (info.genre.isNotBlank()) appendLine("${getString(R.string.info_genre)}: ${info.genre}")
                    if (info.year > 0) {
                        val ym = if (info.month > 0) "${info.year}-${info.month.toString().padStart(2, '0')}" else "${info.year}"
                        appendLine("${getString(R.string.sort_date)}: $ym")
                    }
                    if (info.summary.isNotBlank()) {
                        appendLine()
                        appendLine(info.summary)
                    }
                    appendLine()
                }
                appendLine("${getString(R.string.pages_title)}: ${pageIdx + 1} / ${src.pageCount}")
                if (dims != null && dims.size >= 2) {
                    appendLine("${getString(R.string.info_dimensions)}: ${dims[0]} × ${dims[1]} px")
                }
                if (f.isFile) {
                    appendLine("${getString(R.string.sort_size)}: ${FileUtils.formatSize(this@ViewerActivity, f.length())}")
                }
                append("${getString(R.string.info_path)}: ${f.absolutePath}")
            }
            AlertDialog.Builder(this@ViewerActivity)
                .setTitle(chapterLabel(f))
                .setMessage(text)
                .setPositiveButton(R.string.ok, null)
                .show()
        }
    }

    // ------------------------------------------------------------------ auto-scroll

    private fun toggleAutoScroll() {
        setAutoScroll(!autoScrolling)
    }

    private fun setAutoScroll(enabled: Boolean) {
        if (enabled) startAutoScroll() else stopAutoScroll()
    }

    private fun startAutoScroll() {
        if (source == null || autoScrolling) return
        alignToBottom = false
        autoScrolling = true
        autoRemainder = 0f
        autoLastFrame = SystemClock.uptimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (barsVisible) toggleBars()
        handler.post(autoTick)
        invalidateOptionsMenu()
    }

    private fun stopAutoScroll() {
        if (!autoScrolling) return
        autoScrolling = false
        handler.removeCallbacks(autoTick)
        applyKeepScreenOn()
        invalidateOptionsMenu()
    }

    private val autoTick = object : Runnable {
        override fun run() {
            if (!autoScrolling) return
            if (mode == MODE_PAGED) {
                val count = source?.pageCount ?: return
                val nextPage = if (useSpread()) {
                    SpreadLayout.nextSpreadPage(currentIndex, forward = true, coverSolo = useCoverSolo())
                } else {
                    currentIndex + 1
                }
                if (nextPage >= count) {
                    finishAutoScroll()
                    return
                }
                goTo(nextPage)
                handler.postDelayed(this, PAGE_INTERVAL_MS_BASE / prefs.autoSpeed)
            } else {
                val now = SystemClock.uptimeMillis()
                val dt = (now - autoLastFrame).coerceAtMost(100L)
                autoLastFrame = now
                autoRemainder += prefs.autoSpeed * DP_PER_SECOND_PER_STEP *
                    resources.displayMetrics.density * dt / 1000f
                val px = autoRemainder.toInt()
                autoRemainder -= px
                if (px > 0) vertical.scrollBy(0, px)
                if (!vertical.canScrollVertically(1)) {
                    finishAutoScroll()
                    return
                }
                handler.postDelayed(this, FRAME_MS)
            }
        }
    }

    private fun finishAutoScroll() {
        val next = nextChapter()
        if (prefs.autoNext && next != null) {
            keepAutoScroll = true
            openChapter(next, 0)
        } else {
            stopAutoScroll()
        }
    }

    private fun showAutoSpeedDialog() {
        val slider = Slider(this).apply {
            valueFrom = 1f
            valueTo = 10f
            stepSize = 1f
            value = prefs.autoSpeed.toFloat()
        }
        val builder = AlertDialog.Builder(this)
            .setTitle(getString(R.string.auto_speed_value, prefs.autoSpeed))
            .setPositiveButton(R.string.ok, null)
        val dialog = builder.create()
        slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            prefs.autoSpeed = value.toInt()
            dialog.setTitle(getString(R.string.auto_speed_value, prefs.autoSpeed))
        }
        dialog.setView(sliderContainer(slider))
        dialog.show()
    }

    private fun sliderContainer(slider: Slider): LinearLayout = LinearLayout(this).apply {
        setPadding(48, 24, 48, 0)
        addView(
            slider,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun toggleAutoCrop() = setAutoCrop(!autoCrop)

    private fun setAutoCrop(enabled: Boolean) {
        if (autoCrop == enabled) return
        autoCrop = enabled
        prefs.autoCrop = autoCrop
        invalidateOptionsMenu()
        attach()
    }

    private fun setAutoNext(enabled: Boolean) {
        if (prefs.autoNext == enabled) return
        prefs.autoNext = enabled
        if (enabled) prepareStripAhead() else dropStripAhead()
        invalidateOptionsMenu()
        updateEndCard()
    }

    private fun autoAdvance() = prefs.autoNext && nextChapter() != null

    /** True when any visible [ZoomImageView] inside [rv] is currently zoomed in. */
    private fun isAnyPageZoomed(rv: RecyclerView): Boolean {
        for (i in 0 until rv.childCount) {
            val holder = rv.getChildViewHolder(rv.getChildAt(i)) as? PageAdapter.PageHolder ?: continue
            if (holder.images.any { it.isZoomed }) return true
        }
        return false
    }

    private val overSwipeListener = object : RecyclerView.OnItemTouchListener {
        private var startX = 0f
        private var startY = 0f
        private var tracking = false
        private var startedAtStart = false

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = e.x
                    startY = e.y
                    alignToBottom = false
                    tracking = e.pointerCount == 1 && !isAnyPageZoomed(rv)
                    startedAtStart = tracking && atChapterStart()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    tracking = false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (e.pointerCount > 1 || isAnyPageZoomed(rv)) tracking = false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.x - startX
                    val dy = e.y - startY
                    val strip = mode != MODE_PAGED
                    val along = if (strip) -dy else if (readingDir == "rtl") dx else -dx
                    val across = if (strip) abs(dx) else abs(dy)
                    if (tracking && along > across * 2 && along > swipeMinPx() &&
                        autoAdvance() && atChapterEnd()
                    ) {
                        openNextChapter()
                    } else if (tracking && startedAtStart && -along > across * 2 &&
                        -along > swipeMinPx() && prefs.autoNext && atChapterStart() && prevChapter() != null
                    ) {
                        openPrevChapter(atEnd = true)
                    }
                    tracking = false
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) = Unit

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) = Unit
    }

    private fun atChapterEnd(): Boolean {
        val src = source ?: return false
        return when (mode) {
            MODE_PAGED -> pageToItem(currentIndex) >= pageToItem((src.pageCount - 1).coerceAtLeast(0))
            else -> !vertical.canScrollVertically(1)
        }
    }

    /** True when the reader sits on the first page or at the top of a strip. */
    private fun atChapterStart(): Boolean = when (mode) {
        MODE_PAGED -> currentIndex <= 0
        else -> !vertical.canScrollVertically(-1)
    }

    private fun swipeMinPx(): Int = (resources.displayMetrics.widthPixels / 8f).toInt()

    // ------------------------------------------------------------------ chapter list

    private fun showChapterList() {
        val list = chapters
        if (list.size < 2) return
        val read = library.readSet()
        val current = siblingIndex()
        val labels = list.mapIndexed { i, f ->
            val mark = when {
                i == current -> "▶ "
                f.absolutePath in read -> "✓ "
                else -> "     "
            }
            mark + chapterLabel(f)
        }.toTypedArray()
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.chapters_title)
            .setItems(labels) { _, which -> if (which != current) openChapter(list[which], 0) }
            .setNegativeButton(R.string.close, null)
            .create()
        dialog.show()
        if (current > 2) dialog.listView.setSelection(current - 2)
    }

    // ------------------------------------------------------------------ share / save page

    private fun fileSafeName(): String {
        val base = file?.let { chapterLabel(it) } ?: "page"
        return base.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60) + "_p" + (currentIndex + 1)
    }

    private fun exportPage(share: Boolean) {
        val src = source ?: return
        val page = currentIndex
        val name = fileSafeName()
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outcome: Result<android.net.Uri?> = try {
                withContext(AppDispatchers.io) {
                    val bitmap = src.loadPage(page, EXPORT_W, EXPORT_H)
                    try {
                        if (share) shareUri(bitmap, name) else {
                            savePicture(bitmap, name)
                            null
                        }
                    } finally {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    }
                }.let { Result.success<android.net.Uri?>(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                Result.failure<android.net.Uri?>(e)
            }
            progress.visibility = View.GONE
            val uri = outcome.getOrNull()
            when {
                outcome.isFailure -> toast(R.string.error_load)
                share && uri != null -> startActivity(
                    Intent.createChooser(
                        Intent(Intent.ACTION_SEND)
                            .setType("image/png")
                            .putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                        getString(R.string.menu_share_page)
                    )
                )
                else -> toast(R.string.page_saved)
            }
        }
    }

    private fun shareUri(bitmap: Bitmap, name: String): android.net.Uri {
        val dir = File(cacheDir, SHARE_DIR)
        dir.mkdirs()
        dir.listFiles()?.forEach { runCatching { it.delete() } }
        val out = File(dir, "$name.png")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(this, "$packageName.files", out)
    }

    private fun savePicture(bitmap: Bitmap, name: String) {
        val stamp = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "${name}_$stamp.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ComicReader")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the picture")
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Could not open the picture destination")
                output.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: File(cacheDir, "pictures")
            dir.mkdirs()
            File(dir, "${name}_$stamp.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    // ------------------------------------------------------------------ bookmarks

    private fun toggleBookmark() {
        val f = file ?: return
        val path = f.absolutePath
        if (library.isBookmarked(path, currentIndex)) {
            library.removeBookmark(path, currentIndex)
            toast(R.string.bookmark_removed)
            invalidateOptionsMenu()
            return
        }
        val noteInput = EditText(this).apply {
            hint = getString(R.string.bookmark_note_hint)
            maxLines = 3
            setSingleLine(false)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(
                noteInput,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.bookmark_page_title, currentIndex + 1))
            .setView(container)
            .setPositiveButton(R.string.bookmark_save_with_note) { _, _ ->
                val note = noteInput.text.toString().trim().take(MAX_BOOKMARK_NOTE_LENGTH)
                library.addBookmark(Bookmark(path, f.name, currentIndex, note, System.currentTimeMillis()))
                toast(R.string.bookmark_saved)
                invalidateOptionsMenu()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showBookmarks() {
        val f = file ?: return
        val marks = library.bookmarksFor(f.absolutePath)
        if (marks.isEmpty()) {
            toast(R.string.no_bookmarks_in_book)
            return
        }
        val count = source?.pageCount ?: 0
        val labels = marks.map { mark ->
            val page = getString(R.string.page_indicator, mark.page + 1, count)
            if (mark.note.isBlank()) page else "$page · ${mark.note}"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_bookmark_list)
            .setItems(labels) { _, which -> showBookmarkActions(f, marks[which], count) }
            .setNeutralButton(R.string.clear) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.bookmark_clear_title)
                    .setMessage(R.string.bookmark_clear_message)
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.clear) { _, _ ->
                        marks.forEach { library.removeBookmark(f.absolutePath, it.page) }
                        invalidateOptionsMenu()
                    }
                    .show()
            }
            .show()
    }

    private fun showBookmarkActions(book: File, mark: Bookmark, pageCount: Int) {
        val options = arrayOf(
            getString(R.string.bookmark_go_to_page),
            getString(R.string.bookmark_edit_note),
            getString(R.string.bookmark_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.page_indicator, mark.page + 1, pageCount))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        alignToBottom = false
                        goTo(mark.page)
                    }
                    1 -> editBookmarkNote(book, mark)
                    2 -> {
                        library.removeBookmark(book.absolutePath, mark.page)
                        invalidateOptionsMenu()
                        toast(R.string.bookmark_removed)
                    }
                }
            }
            .show()
    }

    private fun editBookmarkNote(book: File, mark: Bookmark) {
        val input = EditText(this).apply {
            hint = getString(R.string.bookmark_note_hint)
            setText(mark.note)
            maxLines = 3
            setSingleLine(false)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.bookmark_edit_note)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                library.addBookmark(mark.copy(note = input.text.toString().trim().take(MAX_BOOKMARK_NOTE_LENGTH), time = System.currentTimeMillis()))
                toast(R.string.bookmark_saved)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showJumpDialog() {
        val count = source?.pageCount ?: return
        val input = EditText(this)
        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
        input.hint = getString(R.string.jump_hint)
        val container = LinearLayout(this).apply {
            setPadding(48, 24, 48, 0)
            addView(
                input,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_jump)
            .setView(container)
            .setPositiveButton(R.string.ok) { _, _ ->
                val n = input.text.toString().toIntOrNull() ?: return@setPositiveButton
                alignToBottom = false
                goTo((n - 1).coerceIn(0, count - 1))
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ crop / pages

    private fun openPages() {
        val f = file ?: return
        pagesLauncher.launch(
            Intent(this, PagesActivity::class.java)
                .putExtra(PagesActivity.EXTRA_PATH, f.absolutePath)
                .putExtra(PagesActivity.EXTRA_PAGE, currentIndex)
        )
    }

    private fun startCrop() {
        val src = source ?: return
        val page = currentIndex
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val outFile = withContext(AppDispatchers.io) {
                val bitmap = try {
                    src.loadPage(page, 2400, 3200)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                } ?: return@withContext null

                try {
                    runCatching {
                        val out = File(cacheDir, "crop_src.png")
                        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        out
                    }.getOrNull()
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
            progress.visibility = View.GONE
            if (outFile == null) {
                toast(R.string.error_load)
                return@launch
            }
            cropSource = outFile
            cropLauncher.launch(
                Intent(this@ViewerActivity, CropActivity::class.java)
                    .putExtra(CropActivity.EXTRA_PATH, outFile.absolutePath)
            )
        }
    }

    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private const val MAX_BOOKMARK_NOTE_LENGTH = 500
        const val EXTRA_PATH = "extra_path"
        const val EXTRA_PAGE = "extra_page"
        private const val STATE_PATH = "state_path"
        private const val STATE_PAGE = "state_page"

        /** Pass as [EXTRA_PAGE] to open a chapter on its last page (the value is clamped). */
        const val LAST_PAGE = Int.MAX_VALUE

        private const val ALIGN_TRIES = 4
        private const val ALIGN_RETRY_MS = 250L

        private const val SHARE_DIR = "shared"
        private const val EXPORT_W = 2400
        private const val EXPORT_H = 3200
        private const val MAX_SESSION_SECONDS = 4L * 3600
        private const val PAGE_INTERVAL_MS_BASE = 13_000L
        private const val DP_PER_SECOND_PER_STEP = 30f
        private const val FRAME_MS = 16L

        private const val MODE_PAGED = "paged"
        private const val MODE_VERTICAL = "vertical"
        private const val MODE_LONGSTRIP = "longstrip"
    }
}
