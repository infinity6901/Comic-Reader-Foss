# ComicReader maintenance and overhaul log

## Overhaul v13 (version 2.2, version code 13)

### Bug, logic, concurrency, memory, layout & build fixes
- **Fixed `CropView` squared density & handle normalization**: Removed the accidental `density * density` multiplication on corner handle radius, hit-test radius, and minimum crop size (`CropView.kt`). Branched `normalize()` by active handle so dragging right/bottom handles inward clamps against `left + minW` / `top + minH` instead of pushing `left`/`top` backward and inverting the selection. Preserved normalized crop coordinates (`[0..1]`) across `onSizeChanged()` and added a Reset button to `CropActivity`.
- **Fixed two-page spread manual stepping**: Tapping left/right zones, pressing Previous/Next Page buttons, or pressing volume keys in two-page spread mode now steps by a full spread (`SpreadLayout.nextSpreadPage()`) instead of incrementing `currentIndex` by `1` (which left the viewer stuck on the same spread for odd steps).
- **Fixed reader toolbar title overwrite**: `ViewerActivity` now sets `supportActionBar?.title` and `Activity.title` alongside `toolbar.title`, preventing `AppCompatActivity.onPostCreate()` from overwriting the chapter name with `"ComicReader"`.
- **Fixed reader window insets & display cutout clipping**: Added a `WindowInsetsCompat` listener for `systemBars() or displayCutout()` on `viewer_root` so `viewer_toolbar`, `viewer_bottom`, and the fullscreen HUD clear status bars, navigation bars, and camera cutouts when fullscreen is toggled off, and enabled `windowLayoutInDisplayCutoutMode="shortEdges"` on `Theme.ComicReader.Viewer`.
- **Fixed `askStartPage` premature read-marking**: Guarded `onPageChanged()` and `updateEndCard()` with `awaitingStartChoice` while the `"Resume page / First page"` dialog is displayed so chapters with saved progress on their final page are not marked read before the user chooses.
- **Fixed Long Strip chapter roll viewport jump & recreation state**: `onScrolled` now triggers `commitStripChapter()` only once `findFirstVisibleItemPosition() >= chapterPages()` (when the finished chapter has completely scrolled off the top edge), and updates `intent` + `onSaveInstanceState` so activity recreation keeps the new chapter. `PageAdapter` also updates `learnedAspect` at `pageIndex == base`.
- **Fixed `ZoomImageView` zoom in `STRETCH` mode & bind flash**: Allowed pinch-to-zoom and double-tap zoom up to `8x` in `FitMode.STRETCH`, and invoked `fit(fitMode)` synchronously in `setImageDrawable()` when view bounds are already known.
- **Fixed `ViewerActivity.startCrop()` bitmap leak & `CropActivity` cleanup**: Recycled the full-resolution crop source bitmap in a `finally` block after writing `crop_src.png`, and recycled `CropActivity`'s working bitmap in `onDestroy()`.
- **Fixed `BitmapUtils.boundsStream()` 96 MB full-entry buffering**: `boundsStream()` now decodes only the image header via `BitmapFactory.decodeStream` with `inJustDecodeBounds = true` instead of decompressing the entire archive entry into a `ByteArray`.
- **Synchronized `PageSource.close()` across all backends**: Added `@Synchronized` and a `closed` state guard to `PdfSource`, `ZipSource`, `RarSource`, and `SevenZipSource`, and moved `close()` calls onto `AppDispatchers.io` so closing a book cannot race with an in-flight background decode or crash `PdfRenderer`.
- **Cached parsed JSON collections in `Library.kt`**: Cached `favorites`, `read`, `history`, and `bookmarks` in memory under `LOCK` so `isBookmarked()` during page turns and `historyMap()` during search filtering no longer re-parse JSON on the main thread.
- **Fixed `ListActivity.onResume()`, Bookmarks tab notes, and async `openRandomUnread()`**: `onResume()` now refreshes all tabs (`Browse`, `Favorites`, `History`, `Bookmarks`) and syncs grid/column changes from Settings. `entryForBookmark()` now populates file size, displays bookmark notes in the subtitle, sorts ties by page number, and supports editing notes or opening file locations from the Bookmarks tab. Moved `openRandomUnread()` onto `AppDispatchers.io` with fallback to unread files in the current folder.
- **Unified Reading Statistics dialog (`StatsDialog.kt`)**: Shared a single `StatsDialog` implementation between `ListActivity` and `SettingsFragment` so daily reading goals, 7-day activity, report sharing, and stats reset are available from both places.
- **Expanded `AndroidManifest.xml`, ProGuard, keystore & localization**: Added `READ_MEDIA_IMAGES`, standard and `application/octet-stream` `.cbz`/`.cbr`/`.cb7` intent filters (for Telegram and file managers), `-keep class org.tukaani.xz.**`, optional environment-variable release keystore support in `app/build.gradle.kts`, stripped UTF-8 BOMs from 20 XML files, and added localized `strings.xml` resources (`ko`, `ja`, `zh-rCN`, `ru`, `es`, `pt`, `fr`, `de`).

### New features added in v2.2
- **Multi-page loose image folders (`FolderImageSource`)**: Folders containing only loose images (`001.jpg`, `002.png`, `003.webp`) now open directly as a single multi-page chapter (configurable via *Settings → Reader → Open image folders as chapters*, or via the folder long-press menu), and tapping an individual image inside such a folder opens the whole folder starting at that image.
- **Solo cover page in Two-Page Spread mode (`coverSolo`)**: Added a *Solo cover page* toggle in the reader sheet, reader menu, and Settings so page 1 stands centered by itself and facing pages (`2–3`, `4–5`) pair accurately; odd final pages also center cleanly.
- **`ComicInfo.xml` metadata parser & Comic Info dialog (`ComicInfo.kt`)**: Automatically parses embedded `ComicInfo.xml` metadata (Title, Series, Number, Writer, Penciller, Genre, Summary, Year/Month, and Manga RTL flag) from `.cbz`, `.cbr`, and `.cb7` archives and displays it alongside page dimensions and file details via *Comic info*.
- **Webtoon vertical whitespace reducer & tall-strip clarity boost**: Enabling *Auto-crop margins* in Long Strip mode now compresses excessive uniform blank vertical gutters while preserving full strip width, and `BitmapUtils` raises the pixel budget for tall webtoon strips (`h >= 2w`) so vertical text stays sharp.
- **Reader Sleep Timer**: Added a configurable sleep timer (`Off`, `15`, `30`, `45`, `60` minutes) in the reader bottom sheet and overflow menu that warns 1 minute before expiry, saves reading progress and statistics, stops auto-scroll, and closes the reader.
- **Fullscreen Mini HUD pill**: Shows a compact `page / total · battery% · HH:mm` status pill in the bottom corner when the main reader bars are hidden in fullscreen (toggleable in the reader sheet, menu, and Settings).
- **Standardized volume keys + Invert Volume Keys option**: Volume Down now advances forward (and smoothly scrolls 75% of screen height in Long Strip mode) while Volume Up goes back, with an *Invert volume key direction* toggle in Settings and the reader sheet.
- **Storage Volume Switcher**: Added *Switch storage…* in the library menu when removable SD card or secondary storage volumes are mounted.

---

## Overhaul v10 (version 2.0, version code 11)

### Correctness fixes from the latest code review
- Fixed continuous/long-strip auto-next indexing: item count now uses the absolute strip end minus the trimmed base, and page binding/prefetch use the absolute end boundary.
- Fixed even-page two-page spread completion checks so reaching the final spread marks the chapter read.
- Prevented incoming `open with` intents from being replayed during activity recreation, and serialized directory/favorites/history/bookmark listing jobs so stale results cannot overwrite newer tabs.
- Made history sorting recency-first regardless of the generic library sort preference.
- Filtered macOS `__MACOSX` and `._*` resource-fork entries from comic archives, sorted folder cover images naturally, and throttled disk-thumbnail cache trimming.
- Validated backup preference values against each key's expected type.
