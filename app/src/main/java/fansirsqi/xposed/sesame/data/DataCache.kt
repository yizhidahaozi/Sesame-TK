package fansirsqi.xposed.sesame.data

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.ObjectMapper
import fansirsqi.xposed.sesame.util.Files
import fansirsqi.xposed.sesame.util.JsonUtil
import fansirsqi.xposed.sesame.util.Log
import java.io.File

/**
 * @author Byseven
 * @date 2025/2/21
 * @apiNote
 */
object DataCache {
    private const val TAG: String = "DataCache"
    private const val FILENAME = "dataCache.json"
    private val FILE_PATH = Files.CONFIG_DIR

    @get:JsonIgnore
    private var init = false
    val dataMap: MutableMap<String, Any> = mutableMapOf()

    private val objectMapper: ObjectMapper = ObjectMapper()

    init {
        load()
    }

    fun <T> saveData(key: String, value: T): Boolean {
        if (value == null) {
            Log.error(TAG, "Value for key '$key' cannot be null.")
            return false
        }
        try {
            dataMap[key] = value as Any
            return save()
        } catch (e: Exception) {
            Log.printStackTrace(TAG, "save data for key '$key' failed", e)
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> getData(key: String, defaultValue: T? = null): T? {
        // 如果未初始化，尝试重新加载
        if (!init) {
            load()
        }
        val d = dataMap[key] as? T ?: defaultValue
        Log.runtime(TAG, "getData $d for key '$key'")
        return d
    }


    @Synchronized
    private fun save(): Boolean {
        val targetFile = File(FILE_PATH, FILENAME)
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        return try {
            // 1. 序列化对象为格式化后的 JSON 字符串
            val json = JsonUtil.formatJson(this) ?: throw IllegalStateException("JSON 序列化失败")
            // 2. 写入临时文件
            tempFile.writeText(json)
            // 3. 原子性替换（在 Unix 系统上 renameTo 是原子操作）
            if (tempFile.exists() && tempFile.length() > 0) {
                // 直接重命名会自动覆盖目标文件（原子操作）
                if (tempFile.renameTo(targetFile)) {
                    true
                } else {
                    Log.error(TAG, "重命名临时文件失败")
                    false
                }
            } else {
                Log.error(TAG, "临时文件写入失败或为空")
                false
            }
        } catch (e: Exception) {
            Log.error(TAG, "保存缓存数据失败：${e.message}")
            false
        }
    }

    private fun cleanUpDataMap() {
        fun Any.deepClean(): Any? {
            return when (this) {
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val mutable = (this as? MutableMap<Any?, Any?>) ?: this.toMutableMap()
                    val entries = mutable.toList() // 复制一份避免并发修改
                    for ((key, value) in entries) {
                        val cleanedValue = value?.deepClean()
                        if (cleanedValue == null) {
                            mutable.remove(key)
                        } else {
                            mutable[key] = cleanedValue
                        }
                    }
                    mutable
                }

                is Collection<*> -> {
                    // 处理 List 和 Set
                    val list = this.filterNotNull().mapNotNull { it.deepClean() }
                    if (list.isNotEmpty()) {
                        if (list.all { it is String }) {
                            // 如果全是字符串，做去重和过滤空值
                            list.distinct().filter { it is String && it.isNotEmpty() }
                        } else {
                            // 否则只保留非空且已清理的元素
                            list.distinct()
                        }
                    } else {
                        emptyList()
                    }
                }

                is String -> ifEmpty { null }
                else -> this
            }
        }

        for ((key, value) in dataMap.toMap()) {
//            Log.runtime(TAG, "【CLEANUP】处理 key: $key, value type: ${value.javaClass}")
            try {
                val cleanedValue = value.deepClean()
                if (cleanedValue == null || (cleanedValue is Collection<*> && cleanedValue.isEmpty())) {
                    dataMap.remove(key)
                } else {
                    dataMap[key] = cleanedValue
                }
            } catch (e: Exception) {
                Log.error(TAG, "清理键 '$key' 时出错: ${e.message}")
                dataMap.remove(key)
            }
        }
    }

    @Synchronized
    fun load(): Boolean {
        if (init) return true
        val oldFile = Files.getTargetFileofDir(Files.MAIN_DIR, FILENAME)
        val targetFile = Files.getTargetFileofDir(FILE_PATH, FILENAME)
        val tempFile = File(targetFile.parent, "${targetFile.name}.tmp")
        if (tempFile.exists()) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (tempFile.renameTo(targetFile)) {
                Log.runtime(TAG, "从临时文件恢复成功。")
            } else {
                Log.error(TAG, "从临时文件恢复失败。")
            }
        }
        var success = false
        try {
            if (targetFile.exists()) {
                var json = Files.readFromFile(targetFile)
                // 防御性检查：文件可能正在被写入或读取时为空，重试一次
                if (json.isNullOrBlank()) {
                    Log.runtime(TAG, "缓存文件读取为空，等待100ms后重试")
                    Thread.sleep(100)  // 短暂等待，让写入操作完成
                    json = Files.readFromFile(targetFile)
                    if (json.isNullOrBlank()) {
                        Log.runtime(TAG, "重试后仍为空，跳过解析等待下次加载")
                        return false
                    }
                }
                objectMapper.readerForUpdating(this).readValue<Any>(json)
                cleanUpDataMap()
                val formatted = JsonUtil.formatJson(this)
                if (formatted != null && formatted != json) {
                    Log.runtime(TAG, "format $TAG config")
                    save() // 使用临时文件写入
                }
                if (oldFile.exists()) oldFile.delete()
            } else if (oldFile.exists()) {
                if (Files.copy(oldFile, targetFile)) {
                    var json = Files.readFromFile(targetFile)
                    // 防御性检查：重试一次
                    if (json.isNullOrBlank()) {
                        Log.runtime(TAG, "旧缓存文件读取为空，等待100ms后重试")
                        Thread.sleep(100)
                        json = Files.readFromFile(targetFile)
                        if (json.isNullOrBlank()) {
                            Log.runtime(TAG, "重试后仍为空，跳过解析等待下次加载")
                            return false
                        }
                    }
                    objectMapper.readerForUpdating(this).readValue<Any>(json)
                    cleanUpDataMap()
                    val formatted = JsonUtil.formatJson(this)
                    if (formatted != null && formatted != json) {
                        Log.runtime(TAG, "format $TAG config")
                        save()
                    }
                    oldFile.delete()
                } else {
                    Log.error(TAG, "copy old config to new config failed")
                    return false
                }
            } else {
                Log.runtime(TAG, "init $TAG config")
                objectMapper.updateValue(this, DataCache)
                val formatted = JsonUtil.formatJson(this)
                if (formatted != null) {
                    save()
                }
            }
            success = true
        } catch (e: Exception) {
            Log.error(TAG, "加载缓存数据失败：${e.message}")
            // 尝试恢复默认配置
            objectMapper.updateValue(this, DataCache)
        } finally {
            init = success
        }
        return success
    }

}