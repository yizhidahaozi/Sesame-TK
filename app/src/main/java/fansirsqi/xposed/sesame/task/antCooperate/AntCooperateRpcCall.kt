package fansirsqi.xposed.sesame.task.antCooperate

import fansirsqi.xposed.sesame.hook.RequestManager.requestString
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object AntCooperateRpcCall {
    private const val VERSION = "20230501"

    fun queryUserCooperatePlantList(): String {
        return requestString("alipay.antmember.forest.h5.queryUserCooperatePlantList", "[{}]")
    }

    /**
     * 根据合作id查询合种信息
     *
     * @param coopId 合种ID
     * @return requestString
     */
    fun queryCooperatePlant(coopId: String?): String {
        return requestString(
            "alipay.antmember.forest.h5.queryCooperatePlant",
            "[{\"cooperationId\":\"$coopId\"}]"
        )
    }

    fun cooperateWater(uid: String?, coopId: String?, count: Int): String {
        return requestString(
            "alipay.antmember.forest.h5.cooperateWater",
            ("[{\"bizNo\":\"" + uid + "_" + coopId + "_" + System.currentTimeMillis() + "\",\"cooperationId\":\""
                    + coopId + "\",\"energyCount\":" + count + ",\"source\":\"\",\"version\":\"" + VERSION
                    + "\"}]")
        )
    }

    /**
     * 获取合种浇水量排行
     *
     * @param bizType 参数：D/A,“D”为查询当天，“A”为查询所有
     * @param coopId  合种ID
     * @return x
     */
    fun queryCooperateRank(bizType: String?, coopId: String?): String {
        return requestString(
            "alipay.antmember.forest.h5.queryCooperateRank",
            "[{\"bizType\":\"$bizType\",\"cooperationId\":\"$coopId\",\"source\":\"ch_appcenter__chsub_9patch\"}]"
        )
    }

    /**
     * 召唤队友浇水
     *
     * @param userId        用户ID
     * @param cooperationId 合种ID
     * @return requestString
     */
    @Throws(JSONException::class)
    fun sendCooperateBeckon(userId: String?, cooperationId: String?): String {
        val jo = JSONObject()
        jo.put("bizImage", "https://gw.alipayobjects.com/zos/rmsportal/gzYPfxdAxLrkzFUeVkiY.jpg")
        jo.put("link", "lipays://platformapi/startapp?appId=66666886&url=%2Fwww%2Fcooperation%2Findex.htm%3FcooperationId%3D$cooperationId%26sourceName%3Dcard")
        jo.put("midTitle", "快来给我们的树苗浇水，让它快快长大。")
        jo.put(
            "noticeLink",
            "alipays://platformapi/startapp?appId=60000002&url=https%3A%2F%2Frender.alipay.com%2Fp%2Fc%2F17ussbd8vtfg%2Fmessage.html%3FsourceName%3Dcard&showOptionMenu=NO&transparentTitle=NO"
        )
        jo.put("topTitle", "树苗需要你的呵护")
        jo.put("source", "chInfo_ch_url-https://render.alipay.com/p/yuyan/180020010001247580/home.html")
        jo.put("cooperationId", cooperationId)
        jo.put("userId", userId)
        return requestString(
            "alipay.antmember.forest.h5.sendCooperateBeckon",
            JSONArray().put(jo).toString()
        )
    }

    /**
     * 查询真爱合种首页
     */
    fun queryLoveHome(): String {

        return requestString(
            "alipay.greenmatrix.rpc.h5.love.loveHome",
            "[{\"source\":\"chInfo_ch_appcenter__chsub_9patch\"}]"
        )
    }

    /**
     * 真爱合种浇水
     *
     * @param teamId    队伍ID
     * @param donateNum 浇水数值
     */
    fun loveTeamWater(teamId: String?, donateNum: Int): String {
        return requestString(
            "alipay.greenmatrix.rpc.h5.love.teamWater",
            "[{\"donateNum\":$donateNum,\"source\":\"chInfo_ch_appcenter__chsub_9patch\",\"teamId\":\"$teamId\"}]"
        )
    }
}