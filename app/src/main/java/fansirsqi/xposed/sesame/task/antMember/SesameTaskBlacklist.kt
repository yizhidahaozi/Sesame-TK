package fansirsqi.xposed.sesame.task.antMember

import fansirsqi.xposed.sesame.newutil.DataStore
import fansirsqi.xposed.sesame.util.Log
import com.fasterxml.jackson.core.type.TypeReference

/**
 * 芝麻信用任务黑名单管理器
 * 使用DataStore持久化存储黑名单数据
 */
object SesameTaskBlacklist {
    private const val TAG = "SesameTaskBlacklist"
    private const val BLACKLIST_KEY = "sesame_task_blacklist"
    
    /**
     * 默认黑名单列表（包含常见无法完成的任务）
     */
    private val defaultBlacklist = setOf(
        "每日施肥领水果",           // 需要淘宝操作
        "坚持种水果",              // 需要淘宝操作
        "坚持去玩休闲小游戏",       // 需要游戏操作
        "去AQapp提问",            // 需要下载APP
        "去AQ提问",               // 需要下载APP
        "坚持看直播领福利",        // 需要淘宝直播
        "去淘金币逛一逛",          // 需要淘宝操作
        "坚持攒保障金",            // 参数错误：promiseActivityExtCheck
        "芝麻租赁下单得芝麻粒",     // 需要租赁操作
        "去玩小游戏",              // 参数错误：promiseActivityExtCheck
        "浏览租赁商家小程序",       // 需要小程序操作
        "订阅小组件",              // 参数错误：promiseActivityExtCheck
        "租1笔图书",               // 参数错误：promiseActivityExtCheck
        "去订阅芝麻小组件",         // 参数错误：promiseActivityExtCheck
        "坚持攒保障",              // 参数错误：promiseActivityExtCheck（与"坚持攒保障金"类似，防止匹配遗漏）
        "逛租赁会场",              // 操作太频繁：OP_REPEAT_CHECK
        "去花呗翻卡",               // 操作太频繁：OP_REPEAT_CHECK
        "逛网商福利",              // 操作太频繁：OP_REPEAT_CHECK
        "领视频红包",              // 操作太频繁：OP_REPEAT_CHECK
        "领点餐优惠",               // 操作太频繁：OP_REPEAT_CHECK
        "去抛竿钓鱼",              // 操作太频繁：OP_REPEAT_CHECK
        "逛商家积分兑好物",         // 操作太频繁：OP_REPEAT_CHECK
        "坚持浏览乐游记",           // 操作太频繁：OP_REPEAT_CHECK
        "去体验先用后付",           // 操作太频繁：OP_REPEAT_CHECK
        "0.1元起租会员攒粒",        // 参数错误：ILLEGAL_ARGUMENT
        "完成旧衣回收得现金",        // 参数错误：ILLEGAL_ARGUMENT
        "坚持刷视频赚福利",         // 存在进行中的生活记录：PROMISE_HAS_PROCESSING_TEMPLATE
        "去领支付宝积分",           // 存在进行中的生活记录：PROMISE_HAS_PROCESSING_TEMPLATE
        "去参与花呗活动",           // 存在进行中的生活记录：PROMISE_HAS_PROCESSING_TEMPLATE
        "逛网商领福利金",           // 存在进行中的生活记录：PROMISE_HAS_PROCESSING_TEMPLATE
        "去浏览租赁大促会场",        // 存在进行中的生活记录：PROMISE_HAS_PROCESSING_TEMPLATE
        "逛一逛免费领点餐优惠"        // 存在进行中的生活记录：PROMISE_HAS_PROCESSING_TEMPLATE
    )
    
    /**
     * 获取黑名单列表
     */
    fun getBlacklist(): MutableSet<String> {
        return try {
            DataStore.getOrCreate(BLACKLIST_KEY, object : TypeReference<MutableSet<String>>() {})
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
            // 如果获取失败，返回默认黑名单
            defaultBlacklist.toMutableSet()
        }
    }
    
    /**
     * 检查任务是否在黑名单中
     * @param taskTitle 任务标题
     * @return true表示在黑名单中，应该跳过
     */
    fun isTaskInBlacklist(taskTitle: String?): Boolean {
        if (taskTitle == null) return false
        
        val blacklist = getBlacklist()
        return blacklist.any { blacklistItem -> 
            taskTitle.contains(blacklistItem)
        }
    }
    
    /**
     * 添加任务到黑名单
     * @param taskTitle 要添加到黑名单的任务标题
     */
    fun addToBlacklist(taskTitle: String) {
        if (taskTitle.isBlank()) return
        
        val blacklist = getBlacklist()
        if (!blacklist.contains(taskTitle)) {
            blacklist.add(taskTitle)
            saveBlacklist(blacklist)
            Log.record(TAG, "已添加任务到黑名单: $taskTitle")
        }
    }

    /**
     * 保存黑名单到DataStore
     */
    private fun saveBlacklist(blacklist: MutableSet<String>) {
        try {
            DataStore.put(BLACKLIST_KEY, blacklist)
        } catch (e: Exception) {
            Log.printStackTrace(TAG, e)
        }
    }
    
    /**
     * 根据错误码自动添加任务到黑名单
     * @param taskTitle 任务标题
     * @param errorCode 错误码
     */
    fun autoAddToBlacklist(taskTitle: String, errorCode: String) {
        if (taskTitle.isBlank()) return
        
        // 检查是否是应该自动加入黑名单的错误码
        val shouldAutoAdd = when (errorCode) {
            "OP_REPEAT_CHECK", "ILLEGAL_ARGUMENT", "PROMISE_HAS_PROCESSING_TEMPLATE" -> true
            else -> false
        }
        
        if (shouldAutoAdd) {
            addToBlacklist(taskTitle)
            val reason = when (errorCode) {
                "OP_REPEAT_CHECK" -> "操作太频繁"
                "ILLEGAL_ARGUMENT" -> "参数错误"
                "PROMISE_HAS_PROCESSING_TEMPLATE" -> "存在进行中的生活记录"
                else -> "未知错误"
            }
            Log.record(TAG, "任务[$taskTitle]因$reason 自动加入黑名单")
        }
    }
    
    /**
     * 获取黑名单大小
     */
    fun getBlacklistSize(): Int {
        return getBlacklist().size
    }
    
    /**
     * 打印当前黑名单
     */
    fun printBlacklist() {
        val blacklist = getBlacklist()
        Log.record(TAG, "当前黑名单共${blacklist.size}项:")
        blacklist.forEachIndexed { index, item ->
            Log.record(TAG, "${index + 1}. $item")
        }
    }
}