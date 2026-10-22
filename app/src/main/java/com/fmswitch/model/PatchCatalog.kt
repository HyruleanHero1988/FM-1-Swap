package com.fmswitch.model

import android.content.res.AssetManager
import android.util.Base64
import com.fmswitch.fm1kit.Dx7Bank
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HostedPatchBank(
    val id: String,
    val name: String,
    val category: String,
    val about: String,
    val file: String,
    val patches: List<String> = emptyList(),
)

@Serializable
private data class HostedPatchCatalog(val banks: List<HostedPatchBank>)

data class LoadedPatchBank(val info: HostedPatchBank, val bank: Dx7Bank)

private val patchJson = Json { ignoreUnknownKeys = true }

fun loadPatchBanks(assets: AssetManager): List<LoadedPatchBank> {
    val text = assets.open("patches/catalog.json").bufferedReader().use { it.readText() }
    val catalog = patchJson.decodeFromString<HostedPatchCatalog>(text)
    return catalog.banks.map { info ->
        val encoded = assets.open("patches/${info.file}").bufferedReader().use { it.readText() }
        LoadedPatchBank(info, Dx7Bank.parse(Base64.decode(encoded, Base64.DEFAULT)))
    }
}
