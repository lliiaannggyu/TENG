package com.tengwear.tengfilemanager.core.prefs

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tengwear.tengfilemanager.core.model.FileKind
import com.tengwear.tengfilemanager.core.model.SortKey
import com.tengwear.tengfilemanager.core.model.SortSpec
import com.tengwear.tengfilemanager.core.theme.SeedTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "teng_file_manager")

/**
 * 偏好存储（DataStore / Preferences）。
 *
 * 为什么不用 SharedPreferences：手表上写盘次数直接影响待机功耗，
 * DataStore 的串行写入 + 无 apply() 抖动更适合"每滚一次列表就存一次排序偏好"的场景。
 * 同时排序偏好按目录存 map，用 JSON 字符串整体落盘，避免 key 爆炸。
 */
class UserPreferences(private val context: Context) {

    data class RecentItem(
        val uri: String,
        val treeUri: String?,
        val name: String,
        val kind: FileKind,
        val openedAt: Long,
        val isDirectory: Boolean,
    )

    data class Prefs(
        val deviceName: String = "TENG 手表",
        val deviceId: String = "",
        val sortByPath: Map<String, SortSpec> = emptyMap(),
        val favorites: Set<String> = emptySet(),
        val recents: List<RecentItem> = emptyList(),
        val lastLocationUri: String? = null,
        val showScrollRail: Boolean = true,
        val lanDiscoveryEnabled: Boolean = false,
        val lowPowerAuto: Boolean = true,
        /** 主题种子色；SYSTEM = 跟随安卓 Material You 官方色阶。 */
        val seedTheme: SeedTheme = SeedTheme.SYSTEM,
    )

    val flow: Flow<Prefs> = context.dataStore.data.map { prefs ->
        Prefs(
            deviceName = prefs[KEY_DEVICE_NAME] ?: "TENG 手表",
            deviceId = prefs[KEY_DEVICE_ID] ?: "",
            sortByPath = decodeSort(prefs[KEY_SORT_MAP]),
            favorites = decodeSet(prefs[KEY_FAVORITES]),
            recents = decodeRecents(prefs[KEY_RECENTS]),
            lastLocationUri = prefs[KEY_LAST_LOCATION],
            showScrollRail = prefs[KEY_SCROLL_RAIL] ?: true,
            lanDiscoveryEnabled = prefs[KEY_LAN_ENABLED] ?: false,
            lowPowerAuto = prefs[KEY_LOW_POWER] ?: true,
            seedTheme = SeedTheme.fromId(prefs[KEY_SEED_THEME]),
        )
    }

    suspend fun ensureDeviceId(): String {
        var id = ""
        context.dataStore.edit { prefs ->
            val existing = prefs[KEY_DEVICE_ID]
            if (existing.isNullOrBlank()) {
                val generated = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
                prefs[KEY_DEVICE_ID] = generated
                id = generated
            } else {
                id = existing
            }
        }
        return id
    }

    suspend fun setDeviceName(name: String) {
        context.dataStore.edit { it[KEY_DEVICE_NAME] = name.take(40) }
    }

    suspend fun setSort(path: String, spec: SortSpec) {
        context.dataStore.edit { prefs ->
            val map = decodeSort(prefs[KEY_SORT_MAP]).toMutableMap()
            map[path] = spec
            prefs[KEY_SORT_MAP] = encodeSort(map)
        }
    }

    suspend fun setFavorite(uri: Uri, favorite: Boolean) {
        context.dataStore.edit { prefs ->
            val set = decodeSet(prefs[KEY_FAVORITES]).toMutableSet()
            if (favorite) set += uri.toString() else set -= uri.toString()
            prefs[KEY_FAVORITES] = JSONArray(set.toList()).toString()
        }
    }

    suspend fun pushRecent(item: RecentItem) {
        context.dataStore.edit { prefs ->
            val list = decodeRecents(prefs[KEY_RECENTS])
                .filterNot { it.uri == item.uri }
                .toMutableList()
            list.add(0, item)
            prefs[KEY_RECENTS] = encodeRecents(list.take(30))
        }
    }

    suspend fun setLastLocation(uri: Uri?) {
        context.dataStore.edit { prefs ->
            if (uri == null) prefs.remove(KEY_LAST_LOCATION) else prefs[KEY_LAST_LOCATION] = uri.toString()
        }
    }

    suspend fun setShowScrollRail(show: Boolean) {
        context.dataStore.edit { it[KEY_SCROLL_RAIL] = show }
    }

    suspend fun setLanDiscoveryEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_LAN_ENABLED] = enabled }
    }

    suspend fun setSeedTheme(theme: SeedTheme) {
        context.dataStore.edit { it[KEY_SEED_THEME] = theme.id }
    }

    // ------------------------------------------------------------ 编解码

    private fun encodeSort(map: Map<String, SortSpec>): String {
        val obj = JSONObject()
        map.forEach { (path, spec) -> obj.put(path, "${spec.key.name}:${spec.descending}") }
        return obj.toString()
    }

    private fun decodeSort(raw: String?): Map<String, SortSpec> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(raw)
            buildMap {
                obj.keys().forEach { key ->
                    val value = obj.optString(key)
                    val parts = value.split(':')
                    val sortKey = runCatching { SortKey.valueOf(parts[0]) }.getOrDefault(SortKey.NAME)
                    put(key, SortSpec(sortKey, parts.getOrNull(1)?.toBoolean() ?: false))
                }
            }
        }.getOrDefault(emptyMap())
    }

    private fun decodeSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun encodeRecents(list: List<RecentItem>): String {
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("uri", item.uri)
                    put("tree", item.treeUri ?: JSONObject.NULL)
                    put("name", item.name)
                    put("kind", item.kind.name)
                    put("at", item.openedAt)
                    put("dir", item.isDirectory)
                }
            )
        }
        return arr.toString()
    }

    private fun decodeRecents(raw: String?): List<RecentItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                RecentItem(
                    uri = obj.optString("uri"),
                    treeUri = if (obj.isNull("tree")) null else obj.optString("tree"),
                    name = obj.optString("name"),
                    kind = runCatching { FileKind.valueOf(obj.optString("kind")) }.getOrDefault(FileKind.OTHER),
                    openedAt = obj.optLong("at"),
                    isDirectory = obj.optBoolean("dir"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_SORT_MAP = stringPreferencesKey("sort_map")
        val KEY_FAVORITES = stringPreferencesKey("favorites")
        val KEY_RECENTS = stringPreferencesKey("recents")
        val KEY_LAST_LOCATION = stringPreferencesKey("last_location")
        val KEY_SCROLL_RAIL = booleanPreferencesKey("scroll_rail")
        val KEY_LAN_ENABLED = booleanPreferencesKey("lan_enabled")
        val KEY_LOW_POWER = booleanPreferencesKey("low_power")
        val KEY_SEED_THEME = stringPreferencesKey("seed_theme")
    }
}
