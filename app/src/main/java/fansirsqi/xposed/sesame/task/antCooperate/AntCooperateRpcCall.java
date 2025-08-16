package fansirsqi.xposed.sesame.task.antCooperate;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fansirsqi.xposed.sesame.hook.RequestManager;
public class AntCooperateRpcCall {
    private static final String VERSION = "20230501";
    public static String queryUserCooperatePlantList() {
        return RequestManager.requestString("alipay.antmember.forest.h5.queryUserCooperatePlantList", "[{}]");
    }
    public static String queryCooperatePlant(String coopId) {
        String args1 = "[{\"cooperationId\":\"" + coopId + "\"}]";
        return RequestManager.requestString("alipay.antmember.forest.h5.queryCooperatePlant", args1);
    }
    public static String cooperateWater(String uid, String coopId, int count) {
        return RequestManager.requestString("alipay.antmember.forest.h5.cooperateWater",
                "[{\"bizNo\":\"" + uid + "_" + coopId + "_" + System.currentTimeMillis() + "\",\"cooperationId\":\""
                        + coopId + "\",\"energyCount\":" + count + ",\"source\":\"\",\"version\":\"" + VERSION
                        + "\"}]");
    }
    /**
     * 获取合种浇水量排行
     * @param bizType 参数：D/A,“D”为查询当天，“A”为查询所有
     * @param coopId 合种ID
     * @return
     */
    public static String queryCooperateRank(String bizType, String coopId) {
        return  RequestManager.requestString("alipay.antmember.forest.h5.queryCooperateRank",
                "[{\"bizType\":\""+ bizType + "\",\"cooperationId\":\"" + coopId + "\",\"source\":\"chInfo_ch_url-https://render.alipay.com/p/yuyan/180020010001247580/home.html\"}]");
    }

    /**
     * 召唤队友浇水
     * @param userId 用户ID
     * @param cooperationId 合种ID
     * @return requestString
     */
    public static String sendCooperateBeckon(String userId, String cooperationId) throws JSONException {
        JSONObject jo = new JSONObject();
        jo.put("bizImage","https://gw.alipayobjects.com/zos/rmsportal/gzYPfxdAxLrkzFUeVkiY.jpg");
        jo.put("link","lipays://platformapi/startapp?appId=66666886&url=%2Fwww%2Fcooperation%2Findex.htm%3FcooperationId%3D"+cooperationId+"%26sourceName%3Dcard");
        jo.put("midTitle","快来给我们的树苗浇水，让它快快长大。");
        jo.put("noticeLink","alipays://platformapi/startapp?appId=60000002&url=https%3A%2F%2Frender.alipay.com%2Fp%2Fc%2F17ussbd8vtfg%2Fmessage.html%3FsourceName%3Dcard&showOptionMenu=NO&transparentTitle=NO");
        jo.put("topTitle","树苗需要你的呵护");
        jo.put("source","chInfo_ch_url-https://render.alipay.com/p/yuyan/180020010001247580/home.html");
        jo.put("cooperationId",cooperationId);
        jo.put("userId",userId);
        return  RequestManager.requestString("alipay.antmember.forest.h5.sendCooperateBeckon",
                new JSONArray().put(jo).toString());
    }

}
