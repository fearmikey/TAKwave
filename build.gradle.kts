// SPDX-License-Identifier: GPL-3.0-or-later
// Top-level build file where you can add configuration options common to all sub-projects/modules.
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RadialGradientPaint
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Point2D
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

/**
 * Renders the store icon (fastlane/metadata/android/en-US/images/icon.png, 512 x 512) from the
 * same geometry as the launcher icon vectors in app/src/main/res/drawable. Run it by hand after
 * changing the icon:  ./gradlew generateFastlaneIcon
 * The PNG is committed; this task is not part of the normal build.
 */
abstract class GenerateFastlaneIcon : DefaultTask() {
    @get:OutputFile
    abstract val output: RegularFileProperty

    @TaskAction
    fun render() {
        System.setProperty("java.awt.headless", "true")
        val size = 512
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        // Launchers show the central 72 of the 108 viewport units; draw that region.
        val scale = size / 72.0
        g.scale(scale, scale)
        g.translate(-18.0, -18.0)

        g.paint = RadialGradientPaint(
            Point2D.Double(54.0, 40.0), 80f, floatArrayOf(0f, 1f),
            arrayOf(Color(0x17, 0x52, 0x5F), Color(0x0B, 0x1D, 0x26)),
        )
        g.fillRect(0, 0, 108, 108)
        g.color = Color(255, 255, 255, 20)
        g.stroke = BasicStroke(0.6f)
        for (v in 18..90 step 18) {
            g.draw(Line2D.Double(0.0, v.toDouble(), 108.0, v.toDouble()))
            g.draw(Line2D.Double(v.toDouble(), 0.0, v.toDouble(), 108.0))
        }

        fun arcs(radius: Double, color: Color) {
            g.color = color
            g.stroke = BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val box = doubleArrayOf(54.0 - radius, 40.0 - radius, radius * 2, radius * 2)
            g.draw(Arc2D.Double(box[0], box[1], box[2], box[3], 140.0, 80.0, Arc2D.OPEN))
            g.draw(Arc2D.Double(box[0], box[1], box[2], box[3], -40.0, 80.0, Arc2D.OPEN))
        }
        arcs(22.0, Color(0x4D, 0xD0, 0xE1, 0x99))
        arcs(14.0, Color(0x4D, 0xD0, 0xE1))

        g.color = Color(0xEC, 0xEF, 0xF1)
        g.stroke = BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Double(54.0, 42.0, 45.0, 80.0))
        g.draw(Line2D.Double(54.0, 42.0, 63.0, 80.0))
        g.stroke = BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Double(49.7, 60.0, 58.3, 60.0))
        g.draw(Line2D.Double(47.4, 70.0, 60.6, 70.0))
        g.draw(Line2D.Double(49.7, 60.0, 60.6, 70.0))
        g.draw(Line2D.Double(58.3, 60.0, 47.4, 70.0))

        g.color = Color(0xFF, 0xB7, 0x4D)
        g.fill(Ellipse2D.Double(54.0 - 4.2, 40.0 - 4.2, 8.4, 8.4))
        g.dispose()

        val file = output.get().asFile
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}

tasks.register<GenerateFastlaneIcon>("generateFastlaneIcon") {
    group = "publishing"
    description = "Renders the 512 px store icon for the Fastlane / F-Droid metadata."
    output.set(layout.projectDirectory.file("fastlane/metadata/android/en-US/images/icon.png"))
}
