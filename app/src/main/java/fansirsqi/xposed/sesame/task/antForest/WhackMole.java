package fansirsqi.xposed.sesame.task.antForest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.ResChecker;

/**
 * 6秒拼手速打地鼠
 * 
 * @author Byseven
 * @date 2025/3/7
 */
public class WhackMole {
    private static final String TAG = WhackMole.class.getSimpleName();

    /**
     * 6秒拼手速 打地鼠

     * 逻辑说明：
     * 1. 开始游戏，获取地鼠列表
     * 2. 优先打有能量球的地鼠（有bubbleId字段的）
     * 3. 等待接近6秒后结算，传入剩余未打的地鼠ID
     */
    public static void startWhackMole() {
        String source = "senlinguangchangdadishu";
        try {
            long startTime = System.currentTimeMillis();
            
            // 1. 开始游戏
            JSONObject response = new JSONObject(AntForestRpcCall.startWhackMole(source));
            if (!response.optBoolean("success")) {
                Log.runtime(TAG, response.optString("resultDesc", "开始游戏失败"));
                return;
            }
            
            JSONArray moleInfoArray = response.optJSONArray("moleInfo");
            if (moleInfoArray == null || moleInfoArray.length() == 0) {
                Log.runtime(TAG, "没有地鼠信息");
                return;
            }
            
            String token = response.optString("token");
            if (token.isEmpty()) {
                Log.runtime(TAG, "未获取到游戏token");
                return;
            }
            
            // 2. 收集地鼠信息
            List<Long> allMoleIds = new ArrayList<>();
            List<Long> bubbleMoleIds = new ArrayList<>();
            
            for (int i = 0; i < moleInfoArray.length(); i++) {
                JSONObject mole = moleInfoArray.getJSONObject(i);
                long moleId = mole.getLong("id");
                allMoleIds.add(moleId);
                // 有bubbleId的地鼠有能量球，优先打
                if (mole.has("bubbleId")) {
                    bubbleMoleIds.add(moleId);
                }
            }
            // 3. 打有能量球的地鼠
            int hitCount = 0;
            for (Long moleId : bubbleMoleIds) {
                try {
                    JSONObject whackResp = new JSONObject(AntForestRpcCall.whackMole(moleId, token, source));
                    if (whackResp.optBoolean("success")) {
                        int energy = whackResp.optInt("energyAmount", 0);
                        hitCount++;
                        Log.forest("森林能量⚡️[打地鼠:" + moleId + " 能量+" + energy + "g]");
                        // 间隔一小段时间再打下一个
                        if (hitCount < bubbleMoleIds.size()) {
                            GlobalThreadPools.sleepCompat(200 + (long)(Math.random() * 300));
                        }
                    }
                } catch (Throwable t) {
                    Log.runtime(TAG, "打地鼠 " + moleId + " 失败");
                }
            }
            
            // 4. 计算剩余未打的地鼠ID
            List<String> remainingMoleIds = new ArrayList<>();
            for (Long moleId : allMoleIds) {
                if (!bubbleMoleIds.contains(moleId)) {
                    remainingMoleIds.add(String.valueOf(moleId));
                }
            }
            // 5. 等待接近6秒后结算
            long elapsedTime = System.currentTimeMillis() - startTime;
            long sleepTime = Math.max(0, 6000 - elapsedTime - 200); // 提前200ms结算
            if (sleepTime > 0) {
                GlobalThreadPools.sleepCompat(sleepTime);
            }
            
            // 6. 结算游戏
            response = new JSONObject(AntForestRpcCall.settlementWhackMole(token, remainingMoleIds, source));
            if (ResChecker.checkRes(TAG, response)) {
                int totalEnergy = response.optInt("totalEnergy", 0);
                int provideEnergy = response.optInt("provideDefaultEnergy", 0);
                Log.forest("森林能量⚡️[6秒拼手速完成 总能量+" + totalEnergy + "g (其中打地鼠+" + 
                          (totalEnergy - provideEnergy) + "g, 默认奖励+" + provideEnergy + "g)]");
            }
            
        } catch (Throwable t) {
            Log.runtime(TAG, "whackMole err");
            Log.printStackTrace(TAG, t);
        }
    }

    /**
     * 关闭6秒拼手速
     */
    public static Boolean closeWhackMole() {
        try {
            JSONObject jo = new JSONObject(AntForestRpcCall.closeWhackMole("senlinguangchangdadishu"));
            if (jo.optBoolean("success")) {
                return true;
            } else {
                Log.runtime(TAG, jo.getString("resultDesc"));
            }
        } catch (Throwable t) {
            Log.printStackTrace(t);
        }
        return false;
    }
}
