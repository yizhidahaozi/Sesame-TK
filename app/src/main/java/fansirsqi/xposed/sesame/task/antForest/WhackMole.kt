package fansirsqi.xposed.sesame.task.antForest;

import android.annotation.SuppressLint;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import fansirsqi.xposed.sesame.util.GlobalThreadPools;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.ResChecker;

/**
 * 6秒拼手速打地鼠（纯净版）
 * - 只打普通地鼠
 * - 每局固定3次
 * - 10局并发，6秒后全结算
 * 
 * @author Ghostxx
 */
public class WhackMole {
    private static final String TAG = WhackMole.class.getSimpleName();
    private static final String SOURCE = "senlinguangchangdadishu";
    
    // ========== 核心配置 ==========
    /** 一次性启动的游戏局数：10局并发 */
    private static final int TOTAL_GAMES = 10;
    
    /** 游戏总时长（毫秒）：严格等待6秒，让所有局完成 */
    private static final int GAME_DURATION_MS = 6000;
    
    /** 每局最多击打次数：3次 */
    private static final int MAX_HITS_PER_GAME = 3;  //改1保底奖励 ？
    
    // ========== 统计 ==========
    /** 累计获得能量：所有被结算的局 */
    private static final AtomicInteger totalEnergyEarned = new AtomicInteger(0);
    
    // ========== 内部类 ==========
    /** 游戏会话：存储单局游戏的token、剩余ID、能量和局号 */
    private record GameSession(String token, List<String> remainingIds, 
                               int whackedEnergy, int roundNumber) {}
    
    // ========== 自动入口 ==========
    @SuppressLint("DefaultLocale")
    public static void startWhackMole() {
        Log.other(TAG, String.format("纯净版打地鼠启动 一次性启动%d局", TOTAL_GAMES));
        
        // 1. 一次性启动所有局
        ThreadPoolExecutor executor = createExecutor(TOTAL_GAMES);
        List<Future<GameSession>> futures = new ArrayList<>();
        
        try {
            for (int i = 1; i <= TOTAL_GAMES; i++) {
                final int gameNum = i;
                futures.add(executor.submit(() -> startSingleRound(gameNum)));
            }
            
            Log.other(TAG, "已启动" + TOTAL_GAMES + "局游戏，等待6秒...");
            
            // 2. 严格等待6秒，让所有局同时完成
            GlobalThreadPools.sleepCompat(GAME_DURATION_MS);
            
            // 3. 快速收集所有结果
            List<GameSession> sessions = new ArrayList<>();
            for (Future<GameSession> future : futures) {
                try {
                    GameSession session = future.get(3, TimeUnit.SECONDS);
                    if (session != null) {
                        sessions.add(session);
                    }
                } catch (Exception e) {
                    Log.other(TAG, "收集游戏结果失败: " + e.getMessage());
                }
            }
            
            if (sessions.isEmpty()) {
                Log.other(TAG, "所有局都失败了！");
                return;
            }
            
            // 4. 按能量从高到低排序
            sessions.sort((a, b) -> Integer.compare(b.whackedEnergy(), a.whackedEnergy()));
            
            // 5. 依次结算所有局（从最高到最低）
            for (GameSession session : sessions) {
                settleBestRound(session);
                totalEnergyEarned.addAndGet(session.whackedEnergy());
                
                // 小间隔，避免结算请求被限流
                if (sessions.indexOf(session) < sessions.size() - 1) {
                    GlobalThreadPools.sleepCompat(100);
                }
            }
            
            Log.forest("森林能量⚡️[6秒完成" + TOTAL_GAMES + "局 总计" + totalEnergyEarned.get() + "g]");
            
        } finally {
            shutdownExecutor(executor);
        }
    }
    
    // ========== 单局游戏 ==========
    private static GameSession startSingleRound(int round) {
        try {
            JSONObject startResp = new JSONObject(AntForestRpcCall.startWhackMole(SOURCE));
            if (!ResChecker.checkRes(TAG + "启动失败:", startResp)) {
                return null;
            }
            
            // 检测：如果用户基础信息缺失，说明服务器限制新开
            JSONObject userBaseInfo = startResp.optJSONObject("userBaseInfo");
            if (userBaseInfo == null) {
                Log.other(TAG, "服务器限制：无法新开游戏，userBaseInfo=null");
                return null;
            }
            
            String token = startResp.optString("token");
            JSONArray moleArray = startResp.optJSONArray("moleInfo");
            if (moleArray == null || moleArray.length() == 0) {
                return null;
            }
            
            // 只取前3个普通地鼠（按数组顺序，不排序）
            List<Long> targetIds = new ArrayList<>();
            for (int i = 0; i < moleArray.length() && targetIds.size() < MAX_HITS_PER_GAME; i++) {
                JSONObject m = moleArray.getJSONObject(i);
                if (m.has("bubbleId")) {
                    targetIds.add(m.getLong("id"));
                }
            }
            
            int totalEnergy = 0;
            int hitCount = 0;
            
            // 击打3次
            for (Long moleId : targetIds) {
                if (hitCount >= MAX_HITS_PER_GAME) break;
                
                int energy = whackMoleSync(moleId, token);
                
                if (energy > 0) {
                    totalEnergy += energy;
                    hitCount++;
                    Log.other(TAG, String.format("第%d局 第%d击 energy=%d", round, hitCount, energy));
                }
            }
            
            Log.other(TAG, String.format("第%d局完成 击打%d次 获得%dg", round, hitCount, totalEnergy));
            
            // 构建剩余ID（全部未击打）
            List<String> remainingIds = new ArrayList<>();
            for (int i = 0; i < moleArray.length(); i++) {
                remainingIds.add(moleArray.getJSONObject(i).getString("id"));
            }
            
            return new GameSession(token, remainingIds, totalEnergy, round);
            
        } catch (Exception e) {
            Log.other(TAG, "第" + round + "局异常: " + e.getMessage());
            return null;
        }
    }
    
    // ========== 同步击打 ==========
    private static int whackMoleSync(long moleId, String token) {
        try {
            JSONObject resp = new JSONObject(
                AntForestRpcCall.whackMole(moleId, token, SOURCE)
            );
            return resp.optBoolean("success") ? resp.optInt("energyAmount", 0) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    // ========== 结算 ==========
    private static void settleBestRound(GameSession session) {
        try {
            JSONObject resp = new JSONObject(
                AntForestRpcCall.settlementWhackMole(session.token, session.remainingIds, SOURCE)
            );
            
            if (ResChecker.checkRes(TAG, resp)) {
                int total = resp.optInt("totalEnergy", 0);
                int provide = resp.optInt("provideDefaultEnergy", 0);
                Log.forest(String.format(
                    "森林能量⚡️[第%d局结算 地鼠%dg 默认%dg 总计%dg]",
                    session.roundNumber, total - provide, provide, total
                ));
            }
        } catch (Exception e) {
            Log.other(TAG, "结算异常: " + e.getMessage());
        }
    }
    
    // ========== 线程池工具 ==========
    private static ThreadPoolExecutor createExecutor(int threads) {
        return new ThreadPoolExecutor(
            threads, threads, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(),
            r -> new Thread(r, "WhackMole-" + System.nanoTime())
        );
    }
    
    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
