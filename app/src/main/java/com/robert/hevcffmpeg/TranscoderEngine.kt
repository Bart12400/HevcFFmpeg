package com.robert.hevcffmpeg

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.FFprobeKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.pow

data class SessionStats(
    var savedMb: Long = 0,
    var countH2H: Int = 0,
    var countA2H: Int = 0,
    var countO2H: Int = 0,
    var countAudioAac: Int = 0,
    var countSkipHevc: Int = 0,
    var countSkipLow: Int = 0,
    var countErrors: Int = 0
)

class TranscoderEngine(
    private val inputFile: File,
    private val mode: String = "mediacodec",
    private val onLog: (String) -> Unit
) {
    private var currentSessionId: Long? = null

    fun cancel() {
        currentSessionId?.let { FFmpegKit.cancel(it) }
    }

    private fun calculateBitrate(w: Int, h: Int): Int {
        return (((w.toDouble() * h.toDouble()).pow(0.75)) * 0.05).toInt()
    }

    fun process(stats: SessionStats): Boolean {
        val fileName = inputFile.name
        onLog("\n> Analiza: $fileName")
        onLog("  Sciezka: ${inputFile.parent}")

        val mediaInfo = FFprobeKit.getMediaInformation(inputFile.absolutePath).mediaInformation
        val videoStream = mediaInfo?.streams?.firstOrNull { it.type == "video" }
        val audioStream = mediaInfo?.streams?.firstOrNull { it.type == "audio" }

        val width = videoStream?.width?.toInt() ?: 1280
        val height = videoStream?.height?.toInt() ?: 720
        val codec = videoStream?.codec?.lowercase() ?: "unknown"
        val vBitrateBps = videoStream?.bitrate?.toLongOrNull() ?: 0L
        val vBitrateKbps = (vBitrateBps / 1000).toInt()
        val aBitrateBps = audioStream?.bitrate?.toLongOrNull() ?: 0L
        val aBitrateKbps = (aBitrateBps / 1000).toInt()

        val targetBrKbps = calculateBitrate(width, height)

        onLog("  Rozdzielczosc: ${width}x${height}")
        onLog("  Kodek: ${codec.uppercase()}")
        onLog("  Bitrate wideo: ${if (vBitrateKbps > 0) "${vBitrateKbps}k" else "UNKNOWN"}")
        onLog("  Rekomendowany HEVC bitrate: ${targetBrKbps}k")
        onLog("  Bitrate audio: ${aBitrateKbps}k")

        val isGif = inputFile.extension.equals("gif", true)
        var skip = false
        var reason = ""

        if (vBitrateKbps > 0) {
            if (codec == "hevc") {
                val threshold = (targetBrKbps * 0.8).toInt()
                if (vBitrateKbps < threshold) {
                    skip = true
                    reason = "HEVC + Optymalny"
                    stats.countSkipHevc++
                }
            } else {
                val threshold = (targetBrKbps * 0.6).toInt()
                if (vBitrateKbps < threshold) {
                    skip = true
                    reason = "V BITRATE LOW"
                    stats.countSkipLow++
                }
            }
        }

        if (skip && !isGif) {
            onLog("  [STATUS] POMINIETO ($reason)")
            return true
        }

        val dir = inputFile.parentFile
        val cleanName = inputFile.nameWithoutExtension.removePrefix("video_")
        val tmpOutput = File(dir, "${cleanName}_temp_HEVC.mp4")
        val finalOutput = File(dir, "${cleanName}_hevc.mp4")

        val encoder = if (mode == "software") "libx265" else "hevc_mediacodec"
        val scaleCmd = if (width > 1920) "-vf scale=1920:-2" else "-vf scale=trunc(iw/2)*2:trunc(ih/2)*2"
        val audioCmd = if (aBitrateBps > 64000) "-c:a aac -b:a 64k" else "-c:a copy"

        val cmd = "-y -i \"${inputFile.absolutePath}\" -map_metadata 0 -c:v $encoder -g 60 -keyint_min 60 -pix_fmt yuv420p -b:v ${targetBrKbps}k $scaleCmd $audioCmd -max_muxing_queue_size 1024 \"${tmpOutput.absolutePath}\""

        onLog("  [START] HEVC ($encoder) @ ${targetBrKbps}k")

        val session = FFmpegKit.execute(cmd)
        currentSessionId = session.sessionId

        if (ReturnCode.isSuccess(session.returnCode)) {
            val oldSize = inputFile.length()
            val newSize = tmpOutput.length()

            if (newSize <= (oldSize * 0.6)) {
                inputFile.delete()
                tmpOutput.renameTo(finalOutput)
                preserveTimestamp(finalOutput)

                val zyskMb = (oldSize - newSize) / (1024 * 1024)
                stats.savedMb += zyskMb

                if (codec == "hevc") stats.countH2H++
                else if (codec == "h264" || codec == "avc") stats.countA2H++
                else stats.countO2H++

                if (aBitrateBps > 64000) stats.countAudioAac++

                onLog("  [OK] ZAMIENIONO: ${oldSize / 1048576} MB -> ${newSize / 1048576} MB (Zysk: ${zyskMb}MB)")
                return true
            } else {
                onLog("  [!] ODRZUCONO: Brak zysku > 40%")
                tmpOutput.delete()
                val skipFile = File(dir, "${cleanName}_skiplowBR.${inputFile.extension}")
                inputFile.renameTo(skipFile)
                return true
            }
        } else {
            onLog("  [!] ODRZUCONO: Blad enkodera (Code: ${session.returnCode})")
            tmpOutput.delete()
            stats.countErrors++
            return false
        }
    }

    private fun preserveTimestamp(file: File) {
        val name = file.name
        val regex = Regex("""^(\d{8})_(\d{6})""")
        val match = regex.find(name)
        if (match != null) {
            try {
                val dateStr = "${match.groupValues[1]}_${match.groupValues[2]}"
                val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                val date = sdf.parse(dateStr)
                if (date != null) {
                    file.setLastModified(date.time)
                }
            } catch (_: Exception) {}
        }
    }
}
