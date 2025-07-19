package fansirsqi.xposed.sesame.util

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Exception
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object FansirsqiUtil {

    private const val HITOKOTO_API_URL = "https://v1.hitokoto.cn"

    /**
     * 获取一言（挂起函数），推荐在协程中使用
     * @return 成功返回句子，失败返回默认句子
     */
    suspend fun getOneWord(): String = withContext(Dispatchers.IO) {
        return@withContext try {
            val connection = URL(HITOKOTO_API_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000  // 5秒连接超时
            connection.readTimeout = 5000     // 5秒读取超时
            // 新增：设置请求头，模拟浏览器行为（部分API可能需要）
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")

            // 先检查响应状态码，避免无效流读取
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("API返回非200状态码：${connection.responseCode}")
            }

            // 读取响应内容
            val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            // 解析JSON数据
            val jsonObject = JSONObject(response)
            val hitokoto = jsonObject.optString(
                "hitokoto",
                " 去年相送，余杭门外，飞雪似杨花。\n今年春尽，杨花似雪，犹不见还家。"
            )
            val from = jsonObject.optString("from", "少年游·润州作代人寄远 苏轼")

            "$hitokoto\n\n                    -----Re: $from"
        } catch (e: Exception) {
            // 打印详细错误日志（便于调试）
            Log.e("FansirsqiUtil", "获取一言失败", e)
            // 返回默认句子
            " 去年相送，余杭门外，飞雪似杨花。\n今年春尽，杨花似雪，犹不见还家。\n\n                    -----Re: 少年游·润州作代人寄远 苏轼"
        }
    }

    /**
     * 生成随机字符串
     * @param length 字符串长度
     */
    fun getRandomString(length: Int): String {
        val charPool: List<Char> = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length)
            .map { kotlin.random.Random.nextInt(0, charPool.size) }
            .map(charPool::get)
            .joinToString("")
    }

}
