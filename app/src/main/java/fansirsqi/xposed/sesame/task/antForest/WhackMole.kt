package fansirsqi.xposed.sesame.task.antForest

import android.annotation.SuppressLint
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import kotlinx.coroutines.*
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import java.util.stream.IntStream
import kotlin.random.Random // 导入随机数生成器

/**
 * 6秒拼手速打地鼠（随机间隔防限流版）
 * 
 * 优化策略：
 * 1. 启动间隔随机200-295ms，避免固定频率被识别出现验证
 * 2. 击打间隔随机50-60ms，模拟人工操作
 * 3. 串行启动+动态间隔，大幅降低限流概率
 */
object WhackMole {
    private const val TAG = "WhackMole"
    
    // ========== 核心配置 ==========
    /** 一次性启动的游戏局数：5局并发 */
    private const val TOTAL_GAMES = 5
    
    /** 游戏总时长（毫秒）：严格等待6秒，让所有局完成 */
    private const val GAME_DURATION_MS = 6000L
    
    /** 每局最多击打次数：3次（设置为0时直接结算，不进行击打） */
    private var MAX_HITS_PER_GAME = 3
       
    // ========== 防限流配置（移除固定值） ==========
    /** 结算间隔：保持固定避免结算堆积 */
    private const val SETTLE_INTERVAL_MS = 100L
    
    // ========== 统计 ==========
    private val totalEnergyEarned = AtomicInteger(0)
    private val globalScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startTime = AtomicLong(0)
    
    // ========== 数据类 ==========
    data class GameSession(
        val token: String,
        val remainingIds: List<String>,
        val whackedEnergy: Int,
        val roundNumber: Int
    )
    
    // ========== 自动入口 ==========
    @SuppressLint("DefaultLocale")
    fun startWhackMole() {
        MAX_HITS_PER_GAME = AntForest.whackMoleHits?.value ?: 3
        Log.other(TAG, "随机间隔版打地鼠 ${TOTAL_GAMES}局 每局击打${MAX_HITS_PER_GAME}次")
        
        globalScope.launch {
            try {
                startTime.set(System.currentTimeMillis())
                // 改造点1：串行启动，每次随机间隔200-300ms
                val sessions = mutableListOf<GameSession>()
                for (roundNum in 1..TOTAL_GAMES) {
                    val session = startSingleRound(roundNum)
                    if (session != null) {
                        sessions.add(session)
                    }
                    
                    if (roundNum < TOTAL_GAMES) {
                        val randomDelay = Random.nextLong(200, 295) // 200-295ms
                        Log.other(TAG, "第${roundNum}局启动完成，等待${randomDelay}ms...")
                        delay(randomDelay)
                    }
                }
                
                if (sessions.isEmpty()) {
                    Log.other(TAG, "所有局都失败了！")
                    return@launch
                }
                
                // 计算剩余等待时间
                val elapsedTime = System.currentTimeMillis() - startTime.get()
                val remainingTime = GAME_DURATION_MS - elapsedTime
                
                if (remainingTime > 0) {
                    Log.other(TAG, "已启动${sessions.size}局，等待${remainingTime}ms凑满6秒...")
                    delay(remainingTime)
                } else {
                    Log.other(TAG, "已启动${sessions.size}局，已超过6秒，立即结算")
                }
                
                // 依次结算
                sessions.sortedByDescending { it.whackedEnergy }
                    .forEachIndexed { index, session ->
                        settleBestRound(session)
                        totalEnergyEarned.addAndGet(session.whackedEnergy)
                        
                        if (index < sessions.size - 1) {
                            delay(SETTLE_INTERVAL_MS)
                        }
                    }
                
                Log.forest("森林能量⚡️[打地鼠${sessions.size}局成功 总计${totalEnergyEarned.get()}g]")
                
            } catch (_: CancellationException) {
                Log.other(TAG, "打地鼠协程被取消")
            } catch (e: Exception) {
                Log.other(TAG, "打地鼠异常: ${e.message}")
            }
        }
    }
    
    // ========== 单局游戏 ==========
    private suspend fun startSingleRound(round: Int): GameSession? = withContext(Dispatchers.IO) {
        try {
            val startResp = JSONObject(AntForestRpcCall.startWhackMole())
            if (!ResChecker.checkRes("$TAG 启动失败:", startResp)) {
                return@withContext null
            }
            
            val userBaseInfo = startResp.optJSONObject("userBaseInfo")
            if (userBaseInfo == null) {
                Log.other(TAG, "服务器限流：userBaseInfo=null，第${round}局失败")
                delay(500L) // 退避
                return@withContext null
            }
            
            val token = startResp.optString("token")
            val moleArray = startResp.optJSONArray("moleArray")
                ?: return@withContext null
            
            var totalEnergy = 0
            var hitCount = 0
            
            if (MAX_HITS_PER_GAME == 0) {
                Log.other(TAG, "第${round}局直接结算模式")
            } else {
                val targetIds = (0 until moleArray.length())
                    .asSequence()
                    .map { moleArray.getJSONObject(it) }
                    .filter { it.has("bubbleId") }
                    .take(MAX_HITS_PER_GAME)
                    .map { it.getLong("id") }
                    .toList()
                
                // 改造点2：击打间隔随机50-60ms
                for (moleId in targetIds) {
                    if (hitCount >= MAX_HITS_PER_GAME) break
                    
                    val energy = whackMoleSync(moleId, token)
                    if (energy > 0) {
                        totalEnergy += energy
                        hitCount++
                        Log.other(TAG, "第${round}局 第${hitCount}击 energy=$energy")
                    }
                    
                    if (hitCount < targetIds.size) {
                        val randomHitDelay = Random.nextLong(50, 61) // 50-60ms
                        delay(randomHitDelay)
                    }
                }
                
                Log.other(TAG, "第${round}局完成 击打${hitCount}次 获得${totalEnergy}g")
            }
            
            val remainingIds = (0 until moleArray.length())
                .map { moleArray.getJSONObject(it).getString("id") }
            GameSession(token, remainingIds, totalEnergy, round)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.other(TAG, "第${round}局异常: ${e.message}")
            null
        }
    }
    
    // ========== 同步击打 ==========
    private suspend fun whackMoleSync(moleId: Long, token: String): Int = withContext(Dispatchers.IO) {
        try {
            val resp = JSONObject(AntForestRpcCall.whackMole(moleId, token))
            if (resp.optBoolean("success")) resp.optInt("energyAmount", 0) else 0
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            0
        }
    }
    
    // ========== 结算 ==========
    private suspend fun settleBestRound(session: GameSession) = withContext(Dispatchers.IO) {
        try {
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
