package fansirsqi.xposed.sesame.task.zhimaTree;

import fansirsqi.xposed.sesame.hook.RequestManager;
import org.json.JSONArray;
import org.json.JSONObject;

public class ZhimaTreeRpcCall {

    private static final String PLAY_INFO = "SwbtxJSo8OOUrymAU%2FHnY2jyFRc%2BkCJ3";
    private static final String REFER = "https://render.alipay.com/p/yuyan/180020010001269849/zmTree.html?caprMode=sync&chInfo=chInfo=ch_zmzltf__chsub_xinyongsyyingxiaowei";

    /**
     * 查询芝麻树首页
     */
    public static String zhimaTreeHomePage() {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "ZHIMA_TREE_HOME_PAGE");
            args.put("playInfo", PLAY_INFO);
            args.put("refer", REFER);
            args.put("extInfo", new JSONObject());

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 净化芝麻树 (消耗净化值)
     * 此处改为通用净化，不再指定垃圾ID
     */
    public static String zhimaTreeCleanAndPush(String treeCode) {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "ZHIMA_TREE_CLEAN_AND_PUSH");
            args.put("playInfo", PLAY_INFO);
            args.put("refer", REFER);

            JSONObject extInfo = new JSONObject();
            // 根据之前的日志和经验，点击净化通常只需要 treeCode 和 点击次数
            // 如果之前需要 trashCode 才能净化，说明那是"清理垃圾"，而您现在需要的是"消耗净化值"
            // 如果参数结构有变，请根据抓包修正。这里假设不需要 specific trash info
            extInfo.put("clickNum", "1");
            extInfo.put("treeCode", treeCode);
            // 如果服务端必须要求 trashCampId，可能需要保留空值或从首页获取
            // extInfo.put("trashCampId", "");

            args.put("extInfo", extInfo);

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 查询做任务赚净化值列表
     */
    public static String queryRentGreenTaskList() {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "RENT_GREEN_TASK_LIST_QUERY");
            args.put("playInfo", PLAY_INFO);
            args.put("refer", REFER);

            JSONObject extInfo = new JSONObject();
            extInfo.put("chInfo", "ch_share__chsub_ALPContact");
            extInfo.put("batchId", "");
            args.put("extInfo", extInfo);

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 完成/领取净化值任务
     * @param stageCode "send" 表示去完成/开始, "receive" 表示领取奖励
     */
    public static String rentGreenTaskFinish(String taskId, String stageCode) {
        try {
            JSONObject args = new JSONObject();
            args.put("operation", "RENT_GREEN_TASK_FINISH");
            args.put("playInfo", PLAY_INFO);
            args.put("refer", REFER);

            JSONObject extInfo = new JSONObject();
            extInfo.put("chInfo", "ch_share__chsub_ALPContact");
            extInfo.put("taskId", taskId);
            extInfo.put("stageCode", stageCode);
            args.put("extInfo", extInfo);

            return RequestManager.requestString("alipay.promoprod.play.trigger",
                    new JSONArray().put(args).toString());
        } catch (Exception e) {
            return null;
        }
    }
}