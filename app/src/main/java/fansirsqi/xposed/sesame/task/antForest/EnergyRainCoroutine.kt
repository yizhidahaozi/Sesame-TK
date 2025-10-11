package fansirsqi.xposed.sesame.task.antForest

import fansirsqi.xposed.sesame.hook.Toast
import fansirsqi.xposed.sesame.util.Log
import fansirsqi.xposed.sesame.util.ResChecker
import fansirsqi.xposed.sesame.util.maps.UserMap
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * 能量雨功能 - Kotlin协程版本
 * 
 * 这是EnergyRain.java的协程版本重构，提供更好的性能和可维护性
 */
object EnergyRainCoroutine {
    private const val TAG = "EnergyRain"
    
    /**
     * 执行能量雨功能
     */
    suspend fun execEnergyRain() {
        try {
            energyRain()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.debug(TAG, "execEnergyRain 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            Log.runtime(TAG, "执行能量雨出错:")
            Log.printStackTrace(TAG, th)
        }
    }

    /**
     * 能量雨主逻辑（协程版本）
     */
    private suspend fun energyRain() {
        try {
            var joEnergyRainHome = JSONObject(AntForestRpcCall.queryEnergyRainHome())
            delay(300) // 替换 Thread.sleep(300)
            
            if (ResChecker.checkRes(TAG, joEnergyRainHome)) {
                if (joEnergyRainHome.getBoolean("canPlayToday")) {
                    startEnergyRain()
                }
                
                if (joEnergyRainHome.getBoolean("canGrantStatus")) {
                    Log.record(TAG, "有送能量雨的机会")
                    val joEnergyRainCanGrantList = JSONObject(AntForestRpcCall.queryEnergyRainCanGrantList())
                    
                    val grantInfos = joEnergyRainCanGrantList.getJSONArray("grantInfos")
                    val giveEnergyRainSet = AntForest.giveEnergyRainList!!.value
                    var granted = false
                    
                    for (j in 0 until grantInfos.length()) {
                        val grantInfo = grantInfos.getJSONObject(j)
                        if (grantInfo.getBoolean("canGrantedStatus")) {
                            val uid = grantInfo.getString("userId")
                            if (giveEnergyRainSet.contains(uid)) {
                                val rainJsonObj = JSONObject(AntForestRpcCall.grantEnergyRainChance(uid))
                                Log.record(TAG, "尝试送能量雨给【${UserMap.getMaskName(uid)}】")
                                
                                if (ResChecker.checkRes(TAG, rainJsonObj)) {
                                    Log.forest("赠送能量雨机会给🌧️[${UserMap.getMaskName(uid)}]#${UserMap.getMaskName(UserMap.currentUid)}")
                                    delay(300) // 替换 Thread.sleep(300)
                                    startEnergyRain()
                                } else {
                                    Log.record(TAG, "送能量雨失败")
                                    Log.runtime(TAG, rainJsonObj.toString())
                                }
                                granted = true
                                break
                            }
                        }
                    }
                    
                    if (!granted) {
                        Log.record(TAG, "今日已无可送能量雨好友")
                    }
                }
            }
            
            // 重新获取状态
            joEnergyRainHome = JSONObject(AntForestRpcCall.queryEnergyRainHome())
            if (ResChecker.checkRes(TAG, joEnergyRainHome) && joEnergyRainHome.getBoolean("canPlayToday")) {
                startEnergyRain()
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.debug(TAG, "energyRain 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            Log.runtime(TAG, "energyRain err:")
            Log.printStackTrace(TAG, th)
        }
    }

    /**
     * 开始能量雨（协程版本）
     */
    private suspend fun startEnergyRain() {
        try {
            Log.forest("开始执行能量雨🌧️")
            val joStart = JSONObject(AntForestRpcCall.startEnergyRain())
            
            if (ResChecker.checkRes(TAG, joStart)) {
                val token = joStart.getString("token")
                val bubbleEnergyList = joStart.getJSONObject("difficultyInfo").getJSONArray("bubbleEnergyList")
                var sum = 0
                
                for (i in 0 until bubbleEnergyList.length()) {
                    sum += bubbleEnergyList.getInt(i)
                }
                
                delay(5000) // 等待5秒
                val resultJson = JSONObject(AntForestRpcCall.energyRainSettlement(sum, token))
                
                if (ResChecker.checkRes(TAG, resultJson)) {
                    val s = "收获能量雨🌧️[${sum}g]"
                    Toast.show(s)
                    Log.forest(s)
                }
                delay(300)
            } else {
                Log.runtime(TAG, "startEnergyRain: $joStart")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 协程取消是正常现象，不记录为错误
            Log.debug(TAG, "startEnergyRain 协程被取消")
            throw e  // 必须重新抛出以保证取消机制正常工作
        } catch (th: Throwable) {
            Log.runtime(TAG, "startEnergyRain err:")
            Log.printStackTrace(TAG, th)
        }
    }
    
    /**
     * 兼容Java调用的包装方法
     */
    @JvmStatic
    fun execEnergyRainCompat() {
        kotlinx.coroutines.runBlocking {
            execEnergyRain()
        }
    }
}