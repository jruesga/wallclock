/*
 * Copyright (C) 2022 Jorge Ruesga
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:Suppress("PrivatePropertyName")

package com.ruesga.dash.wallclock

import io.github.humbleui.skija.Canvas
import io.github.humbleui.skija.Color
import io.github.humbleui.skija.Data
import io.github.humbleui.skija.EncodedImageFormat
import io.github.humbleui.skija.Font
import io.github.humbleui.skija.Paint
import io.github.humbleui.skija.Shader
import io.github.humbleui.skija.Surface
import io.github.humbleui.skija.Typeface
import io.github.humbleui.types.Point
import io.github.humbleui.types.Rect
import org.apache.commons.cli.DefaultParser
import org.apache.commons.cli.HelpFormatter
import org.apache.commons.cli.Option
import org.apache.commons.cli.Options
import java.io.InputStream
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.concurrent.thread
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.math.abs
import kotlin.system.exitProcess

class WallClock(
    private val w: Int,
    private val h: Int,
    private val fps: Int,
    private val maxStreamingDuration: Int,
    private val port: Int,
    private val dir: Path,
) {
    private val DTF = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    private val PAINT = Paint().setAntiAlias(true)
    private val RECT = Rect.makeLTRB(0f, 0f, w.toFloat(), h.toFloat())
    private val CBW = (w / COLORS.size).toFloat()

    private val FPMS = 1_000L / fps

    private var isAlive = true
    private var imageBytes: ByteArray? = null

    private val input = dir.resolve("input")
    private val output = dir.resolve("live.mpd")

    private lateinit var server: WallClockServer
    private lateinit var ffmpeg: Process

    fun run() {
        setUp()
        publish()
        transcode()
        draw()
        write()
        if (maxStreamingDuration != Int.MAX_VALUE) {
            stopTranscoding()
        }
        if (port == -1) {
            cleanUp()
        }
    }

    private fun setUp() {
        // Create the temporaty directory
        if (dir.exists().not()) dir.createDirectory()

        // Create pipes
        if (input.exists().not()) {
            ProcessBuilder()
                .command("mkfifo", input.toAbsolutePath().toString())
                .directory(dir.toFile())
                .inheritIO()
                .start()
                .waitFor()
        }

        // Clean
        Runtime.getRuntime().addShutdownHook(object: Thread("shutdown-thread") {
            override fun run() {
                cleanUp()
            }
        })
    }

    private fun publish() {
        if (port > 0) {
            server = WallClockServer(port, dir)
            server.start()
        }
    }

    private fun stopTranscoding() {
        if (this::ffmpeg.isInitialized) {
            ffmpeg.supportsNormalTermination()
        }
        input.deleteIfExists()
    }

    private fun cleanUp() {
        if (this::server.isInitialized) {
            server.stop()
        }
        if (this::ffmpeg.isInitialized) {
            ffmpeg.destroy()
        }
        input.deleteIfExists()

        // Delete entries
        dir.listDirectoryEntries().filter { file ->
            file.name.endsWith(".mpd") || file.name.endsWith(".m4s")
                    || file.name.endsWith(".m4s.tmp")
        }.forEach { it.deleteIfExists() }
    }

    private fun transcode() {
        val removeAtExit = if (maxStreamingDuration == Int.MAX_VALUE) "1" else "0"
        ffmpeg = ProcessBuilder()
            .command(
                "ffmpeg",
                "-y",
                "-f", "jpeg_pipe",
                "-i", input.toAbsolutePath().toString(),
                "-c:v", "libx264", "-x264opts", "keyint=$fps:min-keyint=$fps:no-scenecut", "-r", "$fps",
                // "-c:a", "aac", "-b:a", "128k",
                "-bf", "1", "-b_strategy", "0", "-sc_threshold", "0", "-pix_fmt", "yuv420p",
                "-map", "0:v:0", "-map", "0:v:0", "-map", "0:v:0", "-map", "0:v:0", //"-map", "0:a:?",
                "-b:v:0", "860k", "-filter:v:0", "scale=-2:432,setsar=1:1", "-profile:v:0", "baseline",
                "-b:v:1", "1850k", "-filter:v:0", "scale=-2:540,setsar=1:1", "-profile:v:1", "main",
                "-b:v:2", "4830k", "-filter:v:0", "scale=-2:720,setsar=1:1", "-profile:v:2", "high",
                "-b:v:3", "7830k", "-filter:v:0", "scale=-2:1080,setsar=1:1", "-profile:v:3", "high",
                "-f", "dash", "-seg_duration", "2", "-streaming", "1", "-window_size", "30",
                "-dash_segment_type", "mp4", "-update_period", "6", "-remove_at_exit", removeAtExit,
                output.toAbsolutePath().toString()
            )
            .inheritIO()
            .start()
    }

    private fun draw() {
        thread(priority = Thread.MAX_PRIORITY) {
            // Initiate graphics
            Surface.makeRasterN32Premul(w, h).use { surface ->
                // Draw the background
                drawBackground(surface.canvas)

                // Draw the gradient
                drawGradient(surface.canvas)

                // Wall clock font and drawing area
                val wcbRect = createWallClackBoxRect()
                createWallClockFont().use { font ->
                    // Fit text in wall clock area
                    val wctPoint = createWallClackTextPoint(wcbRect, font)

                    // Keep emitting images as fast as possible
                    while (isAlive) {
                        // Draw the wall clock
                        drawWallClockBox(surface.canvas, wcbRect)
                        drawWallClock(surface.canvas, wctPoint, font)

                        // Convert the image into bytes
                        surface.makeImageSnapshot().use { image ->
                            image.encodeToData(EncodedImageFormat.JPEG)?.apply {
                                imageBytes = bytes
                            }
                        }
                    }
                }
            }
        }
    }

    private fun write() {
        thread(priority = Thread.MAX_PRIORITY) {
            val os = input.outputStream()
            os.use {
                val startTimestamp = System.nanoTime()
                while (true) {
                    val start = System.nanoTime()

                    // Write the image to the ffmpeg pipeline
                    imageBytes?.let { os.write(it) }

                    // Should delay next frame to complaint with fps?
                    val end = System.nanoTime()
                    val delta = (end - start) / 1_000_000L
                    val delay = FPMS - delta
                    if (delay > 0) {
                        Thread.sleep(delay)
                    }

                    // Should finish the streaming?
                    val streamingDuration = (end - startTimestamp) / 1_000_000_000L
                    if (streamingDuration > maxStreamingDuration) {
                        isAlive = false
                        break
                    }
                }
            }
        }.join()
        isAlive = false
    }

    private fun drawBackground(canvas: Canvas) {
        var r = RECT.withWidth(CBW)
        COLORS.forEach { color ->
            PAINT.color = color
            canvas.drawRect(r, PAINT)
            r = r.offset(CBW, 0f)
        }
    }

    private fun drawGradient(canvas: Canvas) {
        val sx = (CBW * (COLORS.size - 1)) / RECT.width
        val sy = (CBW / 2) / RECT.height
        val r = RECT.scale(sx, sy).let {
            it.offset(CBW / 2, RECT.height - it.height * 2)
        }
        val y = RECT.height / 2
        PAINT.shader = Shader.makeLinearGradient(0f, y, RECT.width, y, intArrayOf(BLACK, WHITE))
        canvas.drawRect(r, PAINT)
        PAINT.shader = null
    }

    private fun drawWallClockBox(canvas: Canvas, r: Rect) {
        PAINT.color = BLACK
        canvas.drawRect(r, PAINT)
    }

    private fun drawWallClock(canvas: Canvas, r: Point, font: Font) {
        val now = DTF.format(LocalTime.now())
        PAINT.color = WHITE
        canvas.drawString(now, r.x, r.y, font, PAINT)
    }

    private fun createWallClackBoxRect(): Rect {
        val sx = (CBW * (COLORS.size - 3)) / RECT.width
        val sy = (CBW / 1.25f) / RECT.height
        return RECT.scale(sx, sy).let {
            it.offset(CBW + CBW / 2, (RECT.height / 2) - (it.height / 2))
        }
    }

    private fun createWallClackTextPoint(rect: Rect, font: Font): Point {
        val crect = rect.scale(0.90f)
        val now = DTF.format(LocalTime.now())
        PAINT.color = WHITE
        var r: Rect
        var max = 240f
        var min = 1f
        var last = -1f
        var value: Float
        while (true) {
            value = min + ((max - min) / 2)
            font.size = value
            r = font.measureText(now, PAINT)
            if (r.width > crect.width) {
                last = value
                max = value
            } else {
                min = value
                if (abs(last - value) <= 1f) break
                last = value
            }
        }

        val x = rect.left + (rect.width / 2) - (r.width / 2) - r.left
        val y = rect.top + rect.height / 2 - (font.metrics.descent + font.metrics.ascent) / 2
        return Point(x, y)
    }

    private fun createWallClockFont(): Font {
        val ttf = "/fonts/RobotoMono-Regular.ttf".asResource().readBytes()
        Typeface.makeFromData(Data.makeFromBytes(ttf)).use { tf ->
            return Font(tf, 1f)
        }
    }

    private companion object {
        private val WHITE: Int = Color.makeRGB(255, 255, 255)
        private val YELLOW: Int = Color.makeRGB(255, 255, 0)
        private val CYAN: Int = Color.makeRGB(0, 255, 255)
        private val GREEN: Int = Color.makeRGB(0, 255, 0)
        private val MAGENTA: Int = Color.makeRGB(255, 0, 255)
        private val RED: Int = Color.makeRGB(255, 0, 0)
        private val BLUE: Int = Color.makeRGB(0, 0, 255)
        private val BLACK: Int = Color.makeRGB(0, 0, 0)
        private val COLORS = listOf(WHITE, YELLOW, CYAN, GREEN, MAGENTA, RED, BLUE, BLACK)

        @JvmStatic
        fun main(args: Array<String>) {
            val options = Options()
                .addOption("vw", "video-width", true, "video width [default: 1920]")
                .addOption("vh", "video-height", true, "video height [default: 1080]")
                .addOption("fps", "frames-per-second", true, "video frames per second [default: 30]")
                .addOption("msd", "max-streaming-duration", true, "end streaming after x secs [default: -1]")
                .addOption("p", "port", true, "enable streaming on port [default: -1]")
                .addOption("v", "version", false, "version")
                .addOption("h", "help", false, "display command line options")
                .addOption("d", "output-dir", true, "output directory")

            val parser = DefaultParser.builder().build()
            try {
                val cmd = parser.parse(options, args)

                var dir = Path.of("./tmp")
                var w = 1920
                var h = 1080
                var fps = 30
                var msd = Integer.MAX_VALUE
                var p = -1
                when {
                    cmd.hasOption("help") -> showHelp(options, 1)
                    cmd.hasOption("v") -> showVersion()
                    cmd.hasOption("d").not() -> showHelp(options, -1)
                    else -> {
                        dir = Path.of(cmd.getOptionValue("d"))
                        w = cmd.getOptionValue("w", w.toString()).toInt()
                        h = cmd.getOptionValue("h", h.toString()).toInt()
                        fps = cmd.getOptionValue("fps", fps.toString()).toInt()
                        msd = cmd.getOptionValue("msd", msd.toString()).toInt()
                        p = cmd.getOptionValue("p", p.toString()).toInt()
                    }
                }

                try {
                    val wallClock = WallClock(w, h, fps, msd, p, dir)
                    wallClock.run()
                } catch (ex: Exception) {
                    ex.printStackTrace()
                }
            } catch (ex: Exception) {
                showHelp(options, -1, ex)
            }
        }

        private fun showHelp(options: Options, exitCode: Int, reason: Throwable? = null) {
            val order = setOf("h", "v", "vw", "vh", "fps", "msd", "p", "d")
            val short = "[-h] [-v] [-vw 1920] [-vh 1080] [-fps 30] [-msd -1] [-p -1] --output-dir <path>"
            HelpFormatter().apply {
                optionComparator = Comparator<Option> { o1, o2 ->
                    order.indexOf(o1.opt).compareTo(order.indexOf(o2.opt))
                }
                printHelp(WallClock::class.java.simpleName + " $short", options)
            }
            reason?.printStackTrace()
            exitProcess(exitCode)
        }

        private fun showVersion() {
            val manifest = String("/META-INF/MANIFEST.MF".asResource().readBytes())
            val version = manifest.lines().first { it.startsWith("Implementation-Version:") }.split(": ")[1]
            println("${WallClock::class.java.simpleName} $version")
            exitProcess(0)
        }

        private fun String.asResource(): InputStream =
            WallClock::class.java.getResourceAsStream(this) as InputStream
    }
}

