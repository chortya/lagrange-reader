package com.vangeaux.lagrange

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Per-book language override for read-aloud voices and hyphenation (book declares Dutch here). */
@RunWith(AndroidJUnit4::class)
class EpubBookLanguageInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val epub = File(context.cacheDir, "book-language-${System.nanoTime()}.epub").also {
        writeEpub(it, language = "nl")
    }
    private val readerKey = "book-language-${System.nanoTime()}"

    @After
    fun cleanUp() {
        EpubBookLanguageStore(context).save(readerKey, null)
        epub.delete()
    }

    // ---- Read-aloud voices ----

    private suspend fun <T> withTtsService(block: suspend (EpubTtsPlaybackService.PlaybackBinder) -> T): T {
        val connected = CompletableDeferred<EpubTtsPlaybackService.PlaybackBinder>()
        val connection = EpubTtsServiceConnection(
            context = context,
            onConnected = { connected.complete(it) },
            onDisconnected = {}
        )
        connection.bind()
        var service: EpubTtsPlaybackService.PlaybackBinder? = null
        try {
            service = withTimeout(5_000L) { connected.await() }
            return block(service)
        } finally {
            withContext(Dispatchers.Main.immediate) { service?.stop(OWNER) }
            connection.unbind()
        }
    }

    private suspend fun openSession(service: EpubTtsPlaybackService.PlaybackBinder, languageTag: String?) {
        val spec = EpubTtsSessionSpec(
            ownerToken = OWNER,
            requestId = EpubTtsRequestSession.begin(),
            accountEpoch = EpubTtsAccountSession.currentEpoch(),
            readerKey = readerKey,
            libraryId = "",
            bookId = null,
            fileId = null,
            filePath = epub.absolutePath,
            title = "Book language",
            initialLocator = null,
            settings = EpubTtsSettings(),
            languageTag = languageTag,
            playWhenReady = false
        )
        withContext(Dispatchers.Main.immediate) {
            EpubTtsPlaybackService.start(
                context = context,
                ownerToken = spec.ownerToken,
                requestId = spec.requestId,
                accountEpoch = spec.accountEpoch,
                readerKey = spec.readerKey,
                title = spec.title
            )
            assertTrue(service.open(spec))
        }
    }

    private suspend fun awaitVoiceLanguage(
        service: EpubTtsPlaybackService.PlaybackBinder,
        expectedBase: String
    ): EpubTtsServiceState {
        var last: EpubTtsServiceState? = null
        repeat(60) {
            last = service.state.value
            if (last?.voiceLanguageTag?.substringBefore('-') == expectedBase) return last!!
            delay(250L)
        }
        throw AssertionError("voice language never became $expectedBase; last ${last?.voiceLanguageTag}")
    }

    private fun assertVoicesAreIn(state: EpubTtsServiceState, base: String) {
        assertTrue(
            "every offered voice is $base: ${state.voices.map { it.languageTag }.distinct()}",
            state.voices.all { it.languageTag.substringBefore('-') == base }
        )
    }

    @Test
    fun readAloudOffersTheBookLanguageVoicesByDefaultAndTheOverrideLive() = runBlocking {
        withTtsService { service ->
            openSession(service, languageTag = null)
            val auto = awaitVoiceLanguage(service, "nl")
            assertVoicesAreIn(auto, "nl")

            withContext(Dispatchers.Main.immediate) { service.setLanguage(OWNER, "en") }
            val english = awaitVoiceLanguage(service, "en")
            assertVoicesAreIn(english, "en")
            assumeTrue("Speech engine offers English voices", english.voices.isNotEmpty())

            withContext(Dispatchers.Main.immediate) { service.setLanguage(OWNER, null) }
            assertVoicesAreIn(awaitVoiceLanguage(service, "nl"), "nl")
        }
    }

    @Test
    fun readAloudStartsInTheOverrideLanguage() = runBlocking {
        withTtsService { service ->
            openSession(service, languageTag = "en")
            assertVoicesAreIn(awaitVoiceLanguage(service, "en"), "en")
        }
    }

    // ---- Reader: hyphenation language and persistence ----

    private fun launch(): ActivityScenario<ReadiumEpubReaderActivity> = ActivityScenario.launch(
        ReadiumEpubReaderActivity.createIntent(
            context = context,
            file = epub,
            title = "Book language",
            readerKey = readerKey,
            launchMode = ReaderLaunchMode.NORMAL,
            initialChapter = 0,
            initialPage = 0,
            initialPageCount = 1,
            initialPercent = null
        )
    )

    private suspend fun pageLanguage(scenario: ActivityScenario<ReadiumEpubReaderActivity>): String? {
        lateinit var activity: ReadiumEpubReaderActivity
        scenario.onActivity { activity = it }
        return withContext(Dispatchers.Main) {
            activity.evaluateReaderJavascript(
                "(function(){var h=document.documentElement;return h.getAttribute('lang')||h.getAttribute('xml:lang')||'';})()"
            )
        }?.trim('"')
    }

    private suspend fun awaitPageLanguage(
        scenario: ActivityScenario<ReadiumEpubReaderActivity>,
        expected: String
    ) {
        var last: String? = null
        repeat(60) {
            last = runCatching { pageLanguage(scenario) }.getOrNull()
            if (last == expected) return
            SystemClock.sleep(250)
        }
        throw AssertionError("page language never became $expected; last $last")
    }

    @Test
    fun overrideChangesThePageLanguageLiveAndIsRememberedPerBook() = runBlocking {
        launch().use { scenario ->
            awaitPageLanguage(scenario, "nl")
            scenario.onActivity { it.setBookLanguageForTest("en") }
            awaitPageLanguage(scenario, "en")
            assertEquals("en", EpubBookLanguageStore(context).read(readerKey))

            // Survives the reader being rebuilt.
            scenario.recreate()
            awaitPageLanguage(scenario, "en")

            scenario.onActivity { it.setBookLanguageForTest(null) }
            awaitPageLanguage(scenario, "nl")
            assertNull(EpubBookLanguageStore(context).read(readerKey))
        }
    }

    private fun writeEpub(target: File, language: String) {
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
            deflated(
                "OEBPS/content.opf",
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="book-id">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                    <dc:identifier id="book-id">book-language-fixture</dc:identifier>
                    <dc:title>Book Language Fixture</dc:title>
                    <dc:language>$language</dc:language>
                    <meta property="dcterms:modified">2026-10-10T00:00:00Z</meta>
                  </metadata>
                  <manifest>
                    <item id="c1" href="Text/chapter-1.xhtml" media-type="application/xhtml+xml"/>
                  </manifest>
                  <spine><itemref idref="c1"/></spine>
                </package>
                """
            )
            deflated(
                "OEBPS/Text/chapter-1.xhtml",
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                  <head><title>Chapter 1</title></head>
                  <body><h1>Chapter 1</h1><p>${"The Director told them everything about the hatchery. ".repeat(80)}</p></body>
                </html>
                """
            )
        }
    }

    private companion object {
        const val OWNER = "book-language-test"
    }
}
