package fansirsqi.xposed.sesame.ui;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import androidx.core.content.ContextCompat;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewFeature;

import java.io.File;

import fansirsqi.xposed.sesame.R;
import fansirsqi.xposed.sesame.newui.WatermarkView;
import fansirsqi.xposed.sesame.util.Files;
import fansirsqi.xposed.sesame.util.LanguageUtil;
import fansirsqi.xposed.sesame.util.Log;
import fansirsqi.xposed.sesame.util.ToastUtil;

public class HtmlViewerActivity extends BaseActivity {
    private static final String TAG = HtmlViewerActivity.class.getSimpleName();
    MyWebView mWebView;
    ProgressBar progressBar;
    private Uri uri;
    private Boolean canClear;
    WebSettings settings = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        LanguageUtil.setLocale(this);
        setContentView(R.layout.activity_html_viewer);
        WatermarkView.Companion.install(this);
        // 初始化 WebView 和进度条
        mWebView = findViewById(R.id.mwv_webview);
        progressBar = findViewById(R.id.pgb_webview);

        setupWebView();
        settings = mWebView.getSettings();

        // 安全设置 WebView
        try {
            if (mWebView != null) {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    try {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
                    } catch (Exception e) {
                        Log.error(TAG, "设置夜间模式失败: " + e.getMessage());
                        Log.printStackTrace(TAG, e);
                    }
                }

                settings.setJavaScriptEnabled(false);
                settings.setDomStorageEnabled(false);
                progressBar.setProgressTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.selection_color)));
                mWebView.setBackgroundColor(ContextCompat.getColor(this, R.color.background));
            }
        } catch (Exception e) {
            Log.error(TAG, "WebView初始化异常: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }

        View contentView = findViewById(android.R.id.content);

        ViewCompat.setOnApplyWindowInsetsListener(contentView, new OnApplyWindowInsetsListener() {
            @NonNull
            @Override
            public WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
                int systemBarsBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;

                mWebView.setPadding(
                        mWebView.getPaddingLeft(),
                        mWebView.getPaddingTop(),
                        mWebView.getPaddingRight(),
                        systemBarsBottom
                );

                return insets;
            }
        });
    }

    /**
     * 设置 WebView 的 WebChromeClient 和进度变化监听
     */
    private void setupWebView() {
        mWebView.setWebChromeClient(
                new WebChromeClient() {
                    @SuppressLint("WrongConstant")
                    @Override
                    public void onProgressChanged(WebView view, int progress) {
                        progressBar.setProgress(progress);
                        if (progress < 100) {
                            setBaseSubtitle("Loading...");
                            progressBar.setVisibility(View.VISIBLE);
                        } else {
                            setBaseSubtitle(mWebView.getTitle());
                            progressBar.setVisibility(View.GONE);
                        }
                    }
                });
    }

    private static String toJsString(String s) {
        if (s == null) return "''";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        sb.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'': sb.append("\\'"); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\f': sb.append("\\f"); break;
                case '\b': sb.append("\\b"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int)c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('\'');
        return sb.toString();
    }

    private static String readAllTextSafe(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return "";
            }
            // 使用传统IO方式，兼容API 24
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            fis.close();
            baos.close();
            return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 智能读取日志文件 - 大文件只读取最后部分
     * @param path 文件路径
     * @return 处理后的文本内容
     */
    private static String readLogTextSmart(String path) {
        try {
            File file = new File(path);
            if (!file.exists()) {
                return "";
            }
            long fileSize = file.length();
            // 配置参数 - 可根据需要调整
            final long FULL_READ_THRESHOLD = 2 * 1024 * 1024;    // 2MB - 超过此大小启用限制
            final long PARTIAL_READ_SIZE = 1024 * 1024;          // 1MB - 限制读取大小
            // 如果文件小于阈值，直接读取全部
            if (fileSize < FULL_READ_THRESHOLD) {
                return readAllTextSafe(path);
            }
            // 大文件只读取最后部分
            Log.runtime("HtmlViewerActivity", "文件较大(" + (fileSize/1024) + "KB)，只显示最后" + (PARTIAL_READ_SIZE/1024) + "KB内容");
            java.nio.charset.Charset cs = java.nio.charset.StandardCharsets.UTF_8;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                long readSize = PARTIAL_READ_SIZE; // 限制读取大小
                long startPos = fileSize - readSize;
                raf.seek(startPos);
                byte[] buffer = new byte[(int) readSize];
                raf.readFully(buffer);
                String content = new String(buffer, cs);
                // 找到第一个完整行的开始位置（避免从行中间开始）
                int firstNewlineIndex = content.indexOf('\n');
                if (firstNewlineIndex > 0 && firstNewlineIndex < content.length() - 1) {
                    content = content.substring(firstNewlineIndex + 1);
                }
                
                // 添加提示信息
                return "📢 文件过大，仅显示最后部分内容 (文件大小: " + (fileSize/1024) + "KB)\n" +
                       "=".repeat(50) + "\n\n" + content;
            }
        } catch (Throwable t) {
            Log.error("HtmlViewerActivity", "智能读取日志文件失败: " + t.getMessage());
            return "读取文件失败: " + t.getMessage();
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onResume() {
        super.onResume();
        // 安全设置WebView
        try {
            Intent intent = getIntent();// 获取传递过来的 Intent
            if (intent != null) {
                if (mWebView != null) {
                    settings.setSupportZoom(true); // 支持缩放
                    settings.setBuiltInZoomControls(true); // 启用内置缩放机制
                    settings.setDisplayZoomControls(false); // 不显示缩放控件
                    settings.setUseWideViewPort(true);// 启用触摸缩放
                    settings.setLoadWithOverviewMode(true);//概览模式加载
                    settings.setTextZoom(85);
                    // 可选夜间模式设置
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        try {
                            WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, true);
                        } catch (Exception e) {
                            Log.error(TAG, "设置夜间模式失败: " + e.getMessage());
                            Log.printStackTrace(TAG, e);
                        }
                    }
                }
                configureWebViewSettings(intent, settings);
                uri = intent.getData();
                if (uri != null) {
//                    mWebView.loadUrl(uri.toString());
/// 日志实时显示 begin
                    settings.setJavaScriptEnabled(true);
                    settings.setDomStorageEnabled(true); // 可选
                    mWebView.loadUrl("file:///android_asset/log_viewer.html");
                    mWebView.setWebChromeClient(new WebChromeClient() {
                        @Override
                        public void onProgressChanged(WebView view, int progress) {
                            progressBar.setProgress(progress);
                            if (progress < 100) {
                                setBaseSubtitle("Loading...");
                                progressBar.setVisibility(View.VISIBLE);
                            } else {
                                setBaseSubtitle(mWebView.getTitle());
                                progressBar.setVisibility(View.GONE);

                                // ★★ 页面已就绪：把现有文件智能加载 ★★
                                if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                                    String path = uri.getPath();
                                    if (path != null && path.endsWith(".log")) {
                                        String all = readLogTextSmart(path); // 使用智能读取方法
                                        String jsArg = toJsString(all);      // 转换为JS字符串
                                        mWebView.evaluateJavascript("setFullText(" + jsArg + ")", null);

                                        // 然后启动增量监听（你在 MyWebView 里实现的）
                                        if (mWebView != null) {
                                            mWebView.startWatchingIncremental(path);
                                            // 或者 mWebView.startWatchingWithObserver(path);
                                        }
                                    }
                                }
                            }
                        }
                    });
/// 日志实时显示 end
                }
                canClear = intent.getBooleanExtra("canClear", false);
            }
        } catch (Exception e) {
            Log.error(TAG, "WebView设置异常: " + e.getMessage());
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 配置 WebView 的设置项
     *
     * @param intent   传递的 Intent
     * @param settings WebView 的设置
     */
    private void configureWebViewSettings(Intent intent, WebSettings settings) {
        if (intent.getBooleanExtra("nextLine", true)) {
            settings.setTextZoom(85);
            settings.setUseWideViewPort(false);
        } else {
            settings.setTextZoom(85);
            settings.setUseWideViewPort(true);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // 创建菜单选项
        // 添加“导出文件”菜单项
        menu.add(0, 1, 1, getString(R.string.export_file));
        // 添加“清空文件”菜单项
        menu.add(0, 2, 2, getString(R.string.clear_file));
        // 添加“用其他浏览器打开”菜单项
        menu.add(0, 3, 3, getString(R.string.open_with_other_browser));
        // 添加“复制URL”菜单项
        menu.add(0, 4, 4, getString(R.string.copy_the_url));
        // 添加“滚动到顶部”菜单项
        menu.add(0, 5, 5, getString(R.string.scroll_to_top));
        // 添加“滚动到底部”菜单项
        menu.add(0, 6, 6, getString(R.string.scroll_to_bottom));
        menu.add(0, 7, 7, "加载完整文件");
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case 1:
                // 导出文件
                exportFile();
                break;
            case 2:
                // 清空文件
                clearFile();
                break;
            case 3:
                // 使用其他浏览器打开
                openWithBrowser();
                break;
            case 4:
                // 复制 URL 到剪贴板
                copyUrlToClipboard();
                break;
            case 5:
                // 滚动到顶部
                mWebView.scrollTo(0, 0);
                break;
            case 6:
                // 滚动到底部
                mWebView.scrollToBottom();
                break;
            case 7:
                // 加载完整文件
                loadFullFile();
                break;
        }
        return true;
    }

    /**
     * 加载完整文件（警告用户可能很慢）
     */
    private void loadFullFile() {
        try {
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme())) {
                String path = uri.getPath();
                if (path != null && path.endsWith(".log")) {
                    File file = new File(path);
                    long fileSize = file.length();
                    
                    final long WARNING_THRESHOLD = 5 * 1024 * 1024; // 5MB - 加载完整文件的警告阈值
                    if (fileSize > WARNING_THRESHOLD) { // 超过阈值警告
                        new android.app.AlertDialog.Builder(this)
                            .setTitle("⚠️ 警告")
                            .setMessage("文件大小: " + (fileSize/1024) + "KB\n\n加载完整文件可能会很慢，甚至导致应用卡死。\n\n确定要继续吗？")
                            .setPositiveButton("继续", (dialog, which) -> {
                                ToastUtil.makeText(this, "正在加载完整文件，请稍候...", Toast.LENGTH_LONG).show();
                                // 在后台线程加载
                                new Thread(() -> {
                                    try {
                                        String all = readAllTextSafe(path);
                                        String jsArg = toJsString(all);
                                        runOnUiThread(() -> {
                                            mWebView.evaluateJavascript("setFullText(" + jsArg + ")", null);
                                            ToastUtil.makeText(this, "完整文件加载完成", Toast.LENGTH_SHORT).show();
                                        });
                                    } catch (Exception e) {
                                        runOnUiThread(() -> {
                                            ToastUtil.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                        });
                                    }
                                }).start();
                            })
                            .setNegativeButton("取消", null)
                            .show();
                    } else {
                        // 小文件直接加载
                        String all = readAllTextSafe(path);
                        String jsArg = toJsString(all);
                        mWebView.evaluateJavascript("setFullText(" + jsArg + ")", null);
                        ToastUtil.makeText(this, "完整文件已加载", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
            ToastUtil.makeText(this, "加载失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 导出当前文件
     */
    private void exportFile() {
        try {
            if (uri != null) {
                String path = uri.getPath();
                Log.runtime(TAG, "URI path: " + path);
                if (path != null) {
                    File exportFile = Files.exportFile(new File(path),true);
                    if (exportFile != null && exportFile.exists()) {
                        ToastUtil.showToast(getString(R.string.file_exported) + exportFile.getPath());
                    } else {
                        Log.runtime(TAG, "导出失败，exportFile 对象为 null 或不存在！");
                    }
                } else {
                    Log.runtime(TAG, "路径为 null！");
                }
            } else {
                Log.runtime(TAG, "URI 为 null！");
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 清空当前文件
     */
    private void clearFile() {
        try {
            if (uri != null) {
                String path = uri.getPath();
                if (path != null) {
                    File file = new File(path);
                    if (Files.clearFile(file)) {
                        ToastUtil.makeText(this, "文件已清空", Toast.LENGTH_SHORT).show();
                        mWebView.reload();
                    }
                }
            }
        } catch (Exception e) {
            Log.printStackTrace(TAG, e);
        }
    }

    /**
     * 使用其他浏览器打开当前 URL
     */
    private void openWithBrowser() {
        if (uri != null) {
            String scheme = uri.getScheme();
            if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                Intent intent = new Intent(Intent.ACTION_VIEW, uri);
                startActivity(intent);
            } else if ("file".equalsIgnoreCase(scheme)) {
                ToastUtil.makeText(this, "该文件不支持用浏览器打开", Toast.LENGTH_SHORT).show();
            } else {
                ToastUtil.makeText(this, "不支持用浏览器打开", Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 复制当前 WebView 的 URL 到剪贴板
     */
    private void copyUrlToClipboard() {
        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboardManager != null) {
            clipboardManager.setPrimaryClip(ClipData.newPlainText(null, mWebView.getUrl()));
            ToastUtil.makeText(this, getString(R.string.copy_success), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.stopWatchingIncremental();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mWebView != null) {
            mWebView.stopWatchingIncremental();
        }
    }

    @SuppressWarnings("ConstantConditions")
    @Override
    protected void onDestroy() {
        // 先停止文件监听，再做 WebView 清理，最后再 super
        if (mWebView != null) {
            mWebView.stopWatchingIncremental();
        }
        if (mWebView != null) {
            try {
                mWebView.loadUrl("about:blank");
                mWebView.stopLoading();
                // 清理WebView的客户端引用以防止内存泄漏
                mWebView.setWebChromeClient(null);
                mWebView.setWebViewClient(null);
                mWebView.destroy();
            } catch (Throwable ignore) {}
        }
        super.onDestroy();
    }


}
