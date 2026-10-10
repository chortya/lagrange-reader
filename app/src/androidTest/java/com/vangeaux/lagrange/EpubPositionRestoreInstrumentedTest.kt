package com.vangeaux.lagrange

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.readium.r2.shared.publication.Locator

/**
 * Background read-aloud keeps saving the reading position after the reader has stopped and Android
 * has captured its saved instance state. When Android later rebuilds the reader from that state
 * (process death, or the activity being reclaimed), the newer stored position must win.
 */
@RunWith(AndroidJUnit4::class)
class EpubPositionRestoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val epub = File(context.cacheDir, "position-restore-${System.nanoTime()}.epub")
    private val readerKey = "position-restore-${System.nanoTime()}"

    @Before
    fun writeFixture() = writeThreeChapterEpub(epub)

    @After
    fun cleanUp() {
        epub.delete()
        ReadiumEpubTtsPositionStore(context).remove(readerKey)
    }

    private fun launch(): ActivityScenario<ReadiumEpubReaderActivity> = ActivityScenario.launch(
        ReadiumEpubReaderActivity.createIntent(
            context = context,
            file = epub,
            title = "Position restore",
            readerKey = readerKey,
            launchMode = ReaderLaunchMode.NORMAL,
            initialChapter = 0,
            initialPage = 0,
            initialPageCount = 1,
            initialPercent = null
        )
    )

    /** Waits for a stored position in the accepted resource, settled with its book position. */
    private fun awaitStoredHref(message: String, accept: (String) -> Boolean): Locator {
        var last: Locator? = null
        repeat(80) {
            last = ReadiumEpubLocatorStore(context).read(readerKey)
            if (last != null && accept(last!!.href.toString()) && last!!.locations.totalProgression != null) {
                return last!!
            }
            SystemClock.sleep(250)
        }
        throw AssertionError("$message; stored ${last?.href}")
    }

    /** What EpubTtsPlaybackService writes for each utterance while the reader is in the background. */
    private fun simulateBackgroundReadAloudReaching(chapterHref: String): Locator {
        val locator = requireNotNull(
            Locator.fromJSON(
                JSONObject(
                    """{"href":"$chapterHref","type":"application/xhtml+xml",
                       "locations":{"progression":0.5,"totalProgression":0.83}}"""
                )
            )
        )
        ReadiumEpubTtsPositionStore(context).save(readerKey, locator)
        ReadiumEpubLocatorStore(context).save(readerKey, locator)
        return locator
    }

    @Test
    fun restoredReaderPrefersPositionSavedByBackgroundReadAloud() {
        launch().use { scenario ->
            val opened = awaitStoredHref("reader did not open on chapter 1") { it.endsWith("chapter-1.xhtml") }

            // Leave the reader: Android captures the saved instance state at chapter 1.
            scenario.moveToState(Lifecycle.State.CREATED)
            val later = simulateBackgroundReadAloudReaching(
                opened.href.toString().replace("chapter-1.xhtml", "chapter-3.xhtml")
            )

            SystemClock.sleep(2000)
            // Android rebuilds the reader from the saved state captured before read-aloud moved on.
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)

            val restored = awaitStoredHref("reader jumped back to the stale saved-state position") {
                it.endsWith("chapter-3.xhtml")
            }
            SystemClock.sleep(1500)
            val settled = requireNotNull(ReadiumEpubLocatorStore(context).read(readerKey))
            assertEquals(later.href, restored.href)
            assertTrue(
                "reader must stay on the newer position, not overwrite it with the old one: ${settled.href}",
                settled.href.toString().endsWith("chapter-3.xhtml")
            )
        }
    }

    @Test
    fun returningToTheForegroundDoesNotOverwriteThePositionSavedByBackgroundReadAloud() {
        launch().use { scenario ->
            val opened = awaitStoredHref("reader did not open on chapter 1") { it.endsWith("chapter-1.xhtml") }

            scenario.moveToState(Lifecycle.State.CREATED)
            val later = simulateBackgroundReadAloudReaching(
                opened.href.toString().replace("chapter-1.xhtml", "chapter-3.xhtml")
            )
            // Unlock the phone / switch back: the same reader instance starts again.
            scenario.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(2000)

            val stored = requireNotNull(ReadiumEpubLocatorStore(context).read(readerKey))
            assertEquals(
                "the replayed visual position must not replace the newer read-aloud position",
                later.href,
                stored.href
            )
        }
    }

    @Test
    fun anOldReadAloudPositionDoesNotPullTheReaderBackWhenItIsRebuilt() {
        val chapter1 = "OEBPS/Text/chapter-1.xhtml"
        val chapter3 = "OEBPS/Text/chapter-3.xhtml"
        fun locator(href: String, progression: Double, total: Double) = requireNotNull(
            Locator.fromJSON(
                JSONObject(
                    """{"href":"$href","type":"application/xhtml+xml",
                       "locations":{"progression":$progression,"totalProgression":$total}}"""
                )
            )
        )
        // Read-aloud was used early in the book and paused; the reader then read on to chapter 3.
        ReadiumEpubTtsPositionStore(context).save(readerKey, locator(chapter1, 0.4, 0.13))
        ReadiumEpubLocatorStore(context).save(readerKey, locator(chapter3, 0.5, 0.83))

        launch().use { scenario ->
            awaitStoredHref("reader did not open on chapter 3") { it.endsWith("chapter-3.xhtml") }
            // Android rebuilds the reader (rotation, fold, or reopening after process death).
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.recreate()
            scenario.moveToState(Lifecycle.State.RESUMED)
            SystemClock.sleep(3000)
            val stored = requireNotNull(ReadiumEpubLocatorStore(context).read(readerKey))
            assertTrue(
                "reader jumped to the old read-aloud position: ${stored.href}",
                stored.href.toString().endsWith("chapter-3.xhtml")
            )
        }
    }

    @Test
    fun configurationRecreationStillRestoresTheCurrentPosition() {
        launch().use { scenario ->
            val opened = awaitStoredHref("reader did not open") { it.endsWith("chapter-1.xhtml") }
            scenario.recreate()
            val restored = awaitStoredHref("recreation lost the position") { it.endsWith("chapter-1.xhtml") }
            assertEquals(opened.href, restored.href)
        }
    }

    private fun writeThreeChapterEpub(target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val mimetype = "application/epub+zip".toByteArray()
            zip.putNextEntry(
                ZipEntry("mimetype").apply {
                    method = ZipEntry.STORED
                    size = mimetype.size.toLong()
                    compressedSize = mimetype.size.toLong()
                    crc = CRC32().apply { update(mimetype) }.value
                }
            )
            zip.write(mimetype)
            zip.closeEntry()
            fun deflated(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.trimIndent().toByteArray())
                zip.closeEntry()
            }
            deflated(
                "META-INF/container.xml",
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """
            )
            val chapters = (1..3)
            deflated(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">position-restore-fixture</dc:identifier>
                    <dc:title>Position Restore Fixture</dc:title>
                    <dc:language>en</dc:language>
                    <meta property="dcterms:modified">2026-10-10T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    ${chapters.joinToString("") { """<item id="c$it" href="Text/chapter-$it.xhtml" media-type="application/xhtml+xml"/>""" }}
                  </manifest>
                  <spine>${chapters.joinToString("") { """<itemref idref="c$it"/>""" }}</spine>
                </package>
                """
            )
            chapters.forEach { index ->
                deflated(
                    "OEBPS/Text/chapter-$index.xhtml",
                    """
                    <html xmlns="http://www.w3.org/1999/xhtml">
                      <head><title>Chapter $index</title></head>
                      <body><h1>Chapter $index</h1><p>${"Chapter $index reading content. ".repeat(300)}</p></body>
                    </html>
                    """
                )
            }
        }
    }
}
