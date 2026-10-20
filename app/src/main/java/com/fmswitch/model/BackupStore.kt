package com.fmswitch.model

import android.content.Context
import com.fmswitch.fm1kit.BaudGirlPatternBank
import com.fmswitch.fm1kit.BaudGirlSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class DeviceBackup(
    val id: String,
    val identity: String,
    val savedAt: Long,
    val presetsFile: String? = null,
    val patternsFile: String? = null,
    val firmwareFile: String? = null,
    val firmwareName: String? = null,
    val firmwareProduct: String? = null,
    val presetCount: Int = 0,
    val patternCount: Int = 0,
) {
    val title: String
        get() {
            val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(savedAt))
            return "$identity · $day"
        }

    val summary: String
        get() = buildList {
            if (firmwareName != null) add(firmwareName)
            if (presetCount > 0) add("$presetCount presets")
            if (patternCount > 0) add("$patternCount patterns")
            if (isEmpty()) add("identity only")
        }.joinToString("  ·  ")
}

class BackupStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val directory = File(context.filesDir, "backups").apply { mkdirs() }
    private val indexFile = File(directory, "index.json")
    var items: List<DeviceBackup> = emptyList()
        private set

    init {
        load()
    }

    fun folder(backup: DeviceBackup): File = File(directory, backup.id).apply { mkdirs() }

    fun file(backup: DeviceBackup, name: String): File = File(folder(backup), name)

    fun save(
        identity: String,
        snapshot: BaudGirlSnapshot?,
        firmware: StoredFirmware?,
        firmwareBytes: ByteArray?,
    ): DeviceBackup {
        val id = UUID.randomUUID().toString()
        val folder = File(directory, id).apply { mkdirs() }
        var presetsName: String? = null
        var patternsName: String? = null
        var firmwareName: String? = null
        if (snapshot != null) {
            presetsName = "presets.syx"
            patternsName = "patterns.syx"
            File(folder, presetsName).writeBytes(snapshot.presetsFile())
            File(folder, patternsName).writeBytes(snapshot.patternsFile())
        }
        if (firmware != null && firmwareBytes != null) {
            firmwareName = firmware.fileName
            File(folder, firmwareName).writeBytes(firmwareBytes)
        }
        val item = DeviceBackup(
            id = id,
            identity = identity,
            savedAt = System.currentTimeMillis(),
            presetsFile = presetsName,
            patternsFile = patternsName,
            firmwareFile = firmwareName,
            firmwareName = firmware?.let { "${it.name} ${it.version}" },
            firmwareProduct = firmware?.product,
            presetCount = snapshot?.presets?.size ?: 0,
            patternCount = if (snapshot != null) 16 else 0,
        )
        items = listOf(item) + items
        persist()
        return item
    }

    fun remove(backup: DeviceBackup) {
        folder(backup).deleteRecursively()
        items = items.filterNot { it.id == backup.id }
        persist()
    }

    fun loadPresets(backup: DeviceBackup): BaudGirlSnapshot? {
        val name = backup.presetsFile ?: return null
        val bytes = file(backup, name).takeIf { it.exists() }?.readBytes() ?: return null
        val presets = BaudGirlSnapshot.parsePresets(bytes)
        val patterns = backup.patternsFile
            ?.let { file(backup, it) }
            ?.takeIf { it.exists() }
            ?.readBytes()
            ?.let { BaudGirlSnapshot.parsePatterns(it) }
            ?: BaudGirlPatternBank()
        return BaudGirlSnapshot(backup.identity, presets, patterns)
    }

    fun loadPatterns(backup: DeviceBackup): BaudGirlPatternBank? {
        val name = backup.patternsFile ?: return null
        val bytes = file(backup, name).takeIf { it.exists() }?.readBytes() ?: return null
        return BaudGirlSnapshot.parsePatterns(bytes)
    }

    private fun load() {
        if (!indexFile.exists()) return
        items = runCatching { json.decodeFromString<List<DeviceBackup>>(indexFile.readText()) }.getOrDefault(emptyList())
            .filter { File(directory, it.id).exists() }
    }

    private fun persist() {
        indexFile.writeText(json.encodeToString(items))
    }
}
