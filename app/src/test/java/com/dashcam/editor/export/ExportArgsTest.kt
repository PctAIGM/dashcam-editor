package com.dashcam.editor.export

import com.dashcam.editor.media.ClipInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ExportArgsTest {
    @get:Rule val temp = TemporaryFolder()

    private val clip = ClipInfo("/cache/input.mp4", "input.mp4", 120_000, 3840, 2160, 60.0, true)

    @Suppress("UNCHECKED_CAST")
    private fun args(entries: List<SelectionEntry>, fast: Boolean = false): List<String> {
        val method = ExportEngine.javaClass.declaredMethods.single { it.name == "buildArgs" }
        method.isAccessible = true
        return (method.invoke(ExportEngine, entries, null, false, false, true,
            ExportOptions(null, null, fastCopy = fast), null, null, File(temp.root, "out.mp4")) as Array<String>).toList()
    }

    @Test fun preciseTrimNearEndStillSeeksWithTwoSecondPreroll() {
        val command = args(listOf(SelectionEntry(clip, 110_000, 113_000)))
        val input = command.indexOf("-i")
        val seeks = command.indices.filter { command[it] == "-ss" }
        assertEquals(2, seeks.size)
        assertEquals("108.000", command[seeks[0] + 1])
        assertEquals("2.000", command[seeks[1] + 1])
        assertFalse(seeks[0] > input)
        assertFalse(seeks[1] < input)
        assertEquals("3.000", command[command.indexOf("-t") + 1])
        assertFalse(command.contains("+faststart"))
    }

    @Test fun trimAtFileStartKeepsOutputSidePreciseSeek() {
        val command = args(listOf(SelectionEntry(clip, 500, 3_500)))
        assertEquals(1, command.count { it == "-ss" })
        assertFalse(command.indexOf("-ss") < command.indexOf("-i"))
        assertEquals("0.500", command[command.indexOf("-ss") + 1])
    }

    @Test fun concatenationHasExplicitSelectedDurationAndNoFullFileRewrite() {
        val command = args(listOf(SelectionEntry(clip, 110_000, 120_000), SelectionEntry(clip, 0, 3_000)))
        assertEquals("13.000", command[command.indexOf("-t") + 1])
        assertFalse(command.contains("+faststart"))
    }

    @Test fun quickModeStillCopiesStreams() {
        val command = args(listOf(SelectionEntry(clip, 110_000, 113_000)), fast = true)
        assertEquals("copy", command[command.indexOf("-c") + 1])
        assertFalse(command.contains("libx264"))
    }
}
