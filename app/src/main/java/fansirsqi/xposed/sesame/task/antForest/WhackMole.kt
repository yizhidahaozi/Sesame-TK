package fansirsqi.xposed.sesame.task.antForest

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 6秒拼手速打地鼠（随机间隔防限流版）
 * 
 * 核心优化策略：
 * 1. 串行启动：避免并发请求触发服务器限流
 * 2. 随机间隔：启动间隔200-295ms随机，击打间隔50-60ms随机，模拟真人操作
 * 3. 智能等待：凑满6秒总时长，确保游戏逻辑完整
 * 4. 限流检测：检测到userBaseInfo=null时增加500ms退避时间
 * 5. 动态模式：支持击打模式（MAX_HITS_PER_GAME>0）和直接结算模式（=0）
 * 
 * @author Ghostxx (优化版)
 */
object WhackMole {
    private const val TAG = "WhackMole"
    
    // ========== 核心配置 ==========
    /** 一次性启动的游戏局数：5局 */
    private const val TOTAL_GAMES = 5
    
    /** 游戏总时长（毫秒）：严格等待6秒，让所有局完成 */
    private const val GAME_DURATION_MS = 6000L
    
    /** 每局最多击打次数：3次（设置为0时直接结算，不进行击打） */
    private var MAX_HITS_PER_GAME = 3
    
    // ========== 防限流配置 ==========
    /** 结算间隔：保持固定100ms，避免结算请求堆积 */
    private const val SETTLE_INTERVAL_MS = 100L
    
    // ========== 统计变量 ==========
    /** 累计获得能量：所有被结算局的能量总和，线程安全 */
    private val totalEnergyEarned = AtomicInteger(0)
    
    /** 全局协程作用域：用于管理所有协程，SupervisorJob确保子协程失败不影响其他 */
    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    /** 记录启动开始时间：用于精确计算已用时长，凑满6秒 */
    private val startTime = AtomicLong(0)
    
    // ========== 数据类 ==========
    /**
     * 游戏会话：存储单局游戏的所有关键信息
     * @param token 本局游戏的唯一凭证
     * @param remainingIds 未击打的地鼠ID列表（结算时需要）
     * @param whackedEnergy 本局已击打获得的能量
     * @param roundNumber 局号（用于日志显示）
     */
    data class GameSession(
        val token: String,
        val remainingIds: List<String>,
        val whackedEnergy: Int,
        val roundNumber: Int
    )
    
    // ========== 自动入口 ==========
    /**
     * 启动打地鼠游戏的主入口
     * 1. 从配置读取击打次数
     * 2. 串行启动所有局（带随机间隔）
     * 3. 等待凑满6秒
     * 4. 按能量从高到低依次结算
     */
    @SuppressLint("DefaultLocale")
    fun startWhackMole() {
        // 从AntForest配置读取自定义击打次数，默认3次
        MAX_HITS_PER_GAME = AntForest.whackMoleHits?.value ?: 3
        
        // 记录当前模式到日志
        val mode = if (MAX_HITS_PER_GAME == 0) "直接结算" else "击打${MAX_HITS_PER_GAME}次"
        Log.other(TAG, "打地鼠启动 ${TOTAL_GAMES}局 ${mode}模式")
        
        // 在IO协程中执行，避免阻塞主线程
        globalScope.launch {
            try {
                // 记录启动时间戳
                startTime.set(System.currentTimeMillis())
                
                // 串行启动每局游戏（避免并发限流）
                val sessions = mutableListOf<GameSession>()
                for (roundNum in 1..TOTAL_GAMES) {
                    val session = startSingleRound(roundNum)
                    if (session != null) {
                        sessions.add(session)
                    }
                    
                    // 局间随机间隔：200-300ms（最后一局后不需要）
                    if (roundNum < TOTAL_GAMES) {
                        val randomDelay = Random.nextLong(200, 295) // [200,295]区间
                        Log.other(TAG, "第${roundNum}局启动完成，等待${randomDelay}ms...")
                        delay(randomDelay)
                    }
                }
                
                // 如果所有局都启动失败，直接退出
                if (sessions.isEmpty()) {
                    Log.other(TAG, "所有局都失败了！")
                    return@launch
                }
                
                // 计算剩余等待时间，凑满6秒
                val elapsedTime = System.currentTimeMillis() - startTime.get()
                val remainingTime = GAME_DURATION_MS - elapsedTime
                
                if (remainingTime > 0) {
                    Log.other(TAG, "已启动${sessions.size}局，等待${remainingTime}ms凑满6秒...")
                    delay(remainingTime)
                } else {
                    Log.other(TAG, "已启动${sessions.size}局，已超过6秒，立即结算")
                }
                
                // 按能量从高到低排序，优先结算高收益局
                sessions.sortedByDescending { it.whackedEnergy }
                    .forEachIndexed { index, session ->
                        settleBestRound(session)
                        totalEnergyEarned.addAndGet(session.whackedEnergy)
                        
                        // 结算间隔：最后一局后不需要
                        if (index < sessions.size - 1) {
                            delay(SETTLE_INTERVAL_MS)
                        }
                    }
                
                // 最终日志：显示成功局数和总能量
                Log.forest("森林能量⚡️[打地鼠${sessions.size}局成功 总计${totalEnergyEarned.get()}g]")
                
            } catch (_: CancellationException) {
                Log.other(TAG, "打地鼠协程被取消")
            } catch (e: Exception) {
                Log.other(TAG, "打地鼠异常: ${e.message}")
            }
        }
    }
    
    // ========== 单局游戏 ==========
    /**
     * 启动单局游戏
     * 1. 调用startWhackMole获取token和地鼠列表
     * 2. 检测userBaseInfo判断服务器是否限流
     * 3. 根据模式选择：击打或直接结算
     * 4. 返回游戏会话对象
     */
    private suspend fun startSingleRound(round: Int): GameSession? = withContext(Dispatchers.IO) {
        try {
            // 调用无参的startWhackMole()（source已内联）
            val startResp = JSONObject(AntForestRpcCall.startWhackMole())
            if (!ResChecker.checkRes("$TAG 启动失败:", startResp)) {
                return@withContext null
            }
            
            // 限流检测：userBaseInfo为null说明服务器限制新开游戏
            val userBaseInfo = startResp.optJSONObject("userBaseInfo")
            if (userBaseInfo == null) {
                Log.other(TAG, "服务器限流：userBaseInfo=null，第${round}局失败")
                delay(500L) // 退避500ms后重试
                return@withContext null
            }
            
            val token = startResp.optString("token")
            val moleArray = startResp.optJSONArray("moleArray")
                ?: return@withContext null
            
            var totalEnergy = 0
            var hitCount = 0
            
            // 模式判断：击打或直接结算
            if (MAX_HITS_PER_GAME == 0) {
                Log.other(TAG, "第${round}局 直接结算模式（跳过击打）")
            } else {
                // 提取带气泡的地鼠ID（前MAX_HITS_PER_GAME个）
                val targetIds = (0 until moleArray.length())
                    .asSequence()
                    .map { moleArray.getJSONObject(it) }
                    .filter { it.has("bubbleId") } // 只选有能量的地鼠
                    .take(MAX_HITS_PER_GAME)
                    .map { it.getLong("id") }
                    .toList()
                
                // 依次击打地鼠（带随机间隔）
                for (moleId in targetIds) {
                    if (hitCount >= MAX_HITS_PER_GAME) break
                    
                    val energy = whackMoleSync(moleId, token)
                    if (energy > 0) {
                        totalEnergy += energy
                        hitCount++
                        Log.other(TAG, "第${round}局 第${hitCount}击 energy=$energy")
                    }
                    
                    // 击打间隔：50-60ms随机（最后一次后无需等待）
                    if (hitCount < targetIds.size) {
                        val randomHitDelay = Random.nextLong(50, 61) // [50,60]区间
                        delay(randomHitDelay)
                    }
                }
                
                Log.other(TAG, "第${round}局完成 击打${hitCount}次 获得${totalEnergy}g")
            }
            
            // 收集剩余ID（结算时需要）
            val remainingIds = (0 until moleArray.length())
                .map { moleArray.getJSONObject(it).getString("id") }
            
            GameSession(token, remainingIds, totalEnergy, round)
        } catch (e: CancellationException) {
            // 协程取消异常需要重新抛出，父协程会处理
            throw e
        } catch (e: Exception) {
            Log.other(TAG, "第${round}局异常: ${e.message}")
            null
        }
    }
    
    // ========== 同步击打 ==========
    /**
     * 同步击打单个地鼠
     * @param moleId 地鼠ID
     * @param token 本局token
     * @return 获得的能量数（失败返回0）
     */
    private suspend fun whackMoleSync(moleId: Long, token: String): Int = withContext(Dispatchers.IO) {
        try {
            // 调用两参的whackMole()（source和version已内联）
            val resp = JSONObject(AntForestRpcCall.whackMole(moleId, token))
            if (resp.optBoolean("success")) resp.optInt("energyAmount", 0) else 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0 // 异常时返回0能量
        }
    }
    
    // ========== 结算 ==========
    /**
     * 结算单局游戏
     * 调用单参的settlementWhackMole()，获取最终能量
     */
    private suspend fun settleBestRound(session: GameSession) = withContext(Dispatchers.IO) {
        try {
            // 调用单参的settlementWhackMole()（其他参数已内联）
            val resp = JSONObject(AntForestRpcCall.settlementWhackMole(session.token))
            
            if (ResChecker.checkRes(TAG, resp)) {
                val total = resp.optInt("totalEnergy", 0)
                val provide = resp.optInt("provideDefaultEnergy", 0)
                Log.forest(
                    "森林能量⚡️[第${session.roundNumber}局结算 " +
                    "地鼠${total - provide}g 默认${provide}g 总计${total}g]"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.other(TAG, "结算异常: ${e.message}")
        }
    }
}
