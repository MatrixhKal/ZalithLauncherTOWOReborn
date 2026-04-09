package com.movtery.zalithlauncher.launch

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import net.kdt.pojavlaunch.Logger
import com.movtery.zalithlauncher.feature.version.Version
import java.io.File

object GraphicsBackendHelper {
    private const val PREFS_NAME = "graphics_backend_prefs"
    private const val KEY_FORCE_OPENGL_PREFIX = "force_opengl_"

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private fun forceKey(versionName: String, gameDir: File): String {
        return KEY_FORCE_OPENGL_PREFIX + versionName + "_" + gameDir.absolutePath.hashCode()
    }

    fun markVulkanFailed(context: Context, versionName: String, gameDir: File) {
        prefs(context).edit().putBoolean(forceKey(versionName, gameDir), true).apply()
    }

    fun clearVulkanFailed(context: Context, versionName: String, gameDir: File) {
        prefs(context).edit().remove(forceKey(versionName, gameDir)).apply()
    }

    fun shouldForceOpenGL(
        context: Context,
        minecraftVersion: Version,
        gameDir: File
    ): Boolean {
        val versionName = minecraftVersion.getVersionName()
        if (!is26_2OrNewer(versionName)) return false

        val savedFailure = prefs(context).getBoolean(forceKey(versionName, gameDir), false)
        if (savedFailure) return true

        return isKnownBadDevice()
    }

    fun applyPreferredBackendIfNeeded(
        context: Context,
        minecraftVersion: Version,
        gameDir: File
    ) {
        val versionName = minecraftVersion.getVersionName()
        if (!shouldForceOpenGL(context, minecraftVersion, gameDir)) {
            Logger.appendToLog("GraphicsBackend: no OpenGL force needed for $versionName")
            return
        }

        val optionsFile = File(gameDir, "options.txt")
        Logger.appendToLog("GraphicsBackend: forcing OpenGL for $versionName")
        Logger.appendToLog("GraphicsBackend: target options file = ${optionsFile.absolutePath}")

        val original = if (optionsFile.exists()) {
            runCatching { optionsFile.readText() }.getOrDefault("")
        } else {
            optionsFile.parentFile?.mkdirs()
            ""
        }

        val updated = when {
            original.contains("""preferredGraphicsBackend:"vulkan"""") -> {
                original.replace(
                    """preferredGraphicsBackend:"vulkan"""",
                    """preferredGraphicsBackend:"opengl""""
                )
            }

            Regex("""(?m)^preferredGraphicsBackend:.*$""").containsMatchIn(original) -> {
                original.replace(
                    Regex("""(?m)^preferredGraphicsBackend:.*$"""),
                    """preferredGraphicsBackend:"opengl""""
                )
            }

            original.isBlank() -> {
                """preferredGraphicsBackend:"opengl"""" + "\n"
            }

            original.endsWith("\n") -> {
                original + """preferredGraphicsBackend:"opengl"""" + "\n"
            }

            else -> {
                original + "\n" + """preferredGraphicsBackend:"opengl"""" + "\n"
            }
        }

        val backendLineBefore = original.lineSequence()
            .firstOrNull { it.startsWith("preferredGraphicsBackend:") }
        Logger.appendToLog("GraphicsBackend: before patch = ${backendLineBefore ?: "<missing>"}")

        runCatching {
            optionsFile.writeText(updated)
        }.onSuccess {
            val backendLineAfter = updated.lineSequence()
                .firstOrNull { it.startsWith("preferredGraphicsBackend:") }
            Logger.appendToLog("GraphicsBackend: after patch = ${backendLineAfter ?: "<missing>"}")
            Logger.appendToLog("GraphicsBackend: options.txt patched successfully")
        }.onFailure { e ->
            Logger.appendToLog("GraphicsBackend: failed to patch options.txt: ${e.message}")
        }
    }

    private fun isKnownBadDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val device = "$manufacturer $model"

        return device.contains("Anbernic RG557", ignoreCase = true)
    }

    private fun is26_2OrNewer(versionName: String): Boolean {
        return Regex("""^26\.(2|[3-9]|\d{2,}).*""").matches(versionName)
    }
}