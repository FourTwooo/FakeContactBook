package com.fourtwo.fakecontactbook.util;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class UpdateChecker {

    /*
     * 正式项目使用这个：
     */
    private static final String RELEASES_API =
            "https://api.github.com/repos/FourTwooo/FakeContactBook/releases";

    /*
     * 测试时如果 FakeContactBook 还没有 releases，可以临时改成：
     * https://api.github.com/repos/FourTwooo/JumpReplay/releases
     */
    private static final String APK_ASSET_NAME = "app-release.apk";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();

    /*
     * 每个 App 进程生命周期只检测一次。
     * 用户关闭 App 进程后下次重新打开，会再次检测。
     */
    private static volatile boolean checkedInThisProcess = false;

    private UpdateChecker() {
    }

    public static void checkOnFirstLaunch(Activity activity) {
        if (activity == null) {
            return;
        }

        if (checkedInThisProcess) {
            return;
        }

        checkedInThisProcess = true;
        check(activity);
    }

    public static void check(Activity activity) {
        if (activity == null) {
            return;
        }

        EXECUTOR.execute(() -> {
            UpdateInfo updateInfo;

            try {
                updateInfo = fetchLatestUpdateInfo(activity);
            } catch (Throwable ignored) {
                /*
                 * 更新检查失败不要打扰用户。
                 * 发布前如果你想调试，可以在这里 Toast 或 log。
                 */
                return;
            }

            if (updateInfo == null || !updateInfo.hasNewVersion) {
                return;
            }

            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    return;
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1
                        && activity.isDestroyed()) {
                    return;
                }

                showUpdateDialog(activity, updateInfo);
            });
        });
    }

    private static UpdateInfo fetchLatestUpdateInfo(Activity activity) throws Exception {
        String json = httpGet(RELEASES_API);

        if (TextUtils.isEmpty(json)) {
            return null;
        }

        JSONArray releases = new JSONArray(json);

        if (releases.length() <= 0) {
            return null;
        }

        JSONObject release = findLatestUsableRelease(releases);

        if (release == null) {
            return null;
        }

        String remoteTag = release.optString("tag_name", "").trim();
        String releaseName = release.optString("name", remoteTag).trim();
        String body = release.optString("body", "").trim();
        String htmlUrl = release.optString("html_url", "").trim();

        if (TextUtils.isEmpty(remoteTag)) {
            return null;
        }

        String localVersion = getLocalVersionName(activity);

        if (!isRemoteVersionNewer(remoteTag, localVersion)) {
            return null;
        }

        ApkAsset apkAsset = findApkAsset(release.optJSONArray("assets"));

        UpdateInfo info = new UpdateInfo();
        info.hasNewVersion = true;
        info.localVersion = localVersion;
        info.remoteTag = remoteTag;
        info.releaseName = TextUtils.isEmpty(releaseName) ? remoteTag : releaseName;
        info.body = body;
        info.releaseHtmlUrl = htmlUrl;
        info.apkDownloadUrl = apkAsset == null ? "" : apkAsset.browserDownloadUrl;
        info.apkSize = apkAsset == null ? 0L : apkAsset.size;
        info.hasApk = apkAsset != null && !TextUtils.isEmpty(apkAsset.browserDownloadUrl);

        return info;
    }

    private static JSONObject findLatestUsableRelease(JSONArray releases) {
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);

            if (release == null) {
                continue;
            }

            if (release.optBoolean("draft", false)) {
                continue;
            }

            if (release.optBoolean("prerelease", false)) {
                continue;
            }

            return release;
        }

        return null;
    }

    private static ApkAsset findApkAsset(JSONArray assets) {
        if (assets == null) {
            return null;
        }

        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.optJSONObject(i);

            if (asset == null) {
                continue;
            }

            String name = asset.optString("name", "").trim();

            if (!APK_ASSET_NAME.equals(name)) {
                continue;
            }

            String downloadUrl = asset.optString("browser_download_url", "").trim();

            if (TextUtils.isEmpty(downloadUrl)) {
                continue;
            }

            ApkAsset result = new ApkAsset();
            result.name = name;
            result.browserDownloadUrl = downloadUrl;
            result.size = asset.optLong("size", 0L);
            return result;
        }

        return null;
    }

    private static String httpGet(String urlText) throws Exception {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();

            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "FakeContactBook-UpdateChecker");

            int code = connection.getResponseCode();

            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            if (input == null) {
                return "";
            }

            StringBuilder builder = new StringBuilder();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(input, StandardCharsets.UTF_8)
            )) {
                String line;

                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }

            if (code < 200 || code >= 300) {
                return "";
            }

            return builder.toString();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String getLocalVersionName(Activity activity) {
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);

            if (!TextUtils.isEmpty(info.versionName)) {
                return info.versionName;
            }
        } catch (Throwable ignored) {
        }

        return "0.0.0";
    }

    private static boolean isRemoteVersionNewer(String remoteTag, String localVersion) {
        String remote = normalizeVersion(remoteTag);
        String local = normalizeVersion(localVersion);

        return compareVersion(remote, local) > 0;
    }

    private static String normalizeVersion(String value) {
        if (value == null) {
            return "0";
        }

        String result = value.trim().toLowerCase(Locale.US);

        while (result.startsWith("v")) {
            result = result.substring(1);
        }

        return result;
    }

    private static int compareVersion(String a, String b) {
        VersionParts va = VersionParts.parse(a);
        VersionParts vb = VersionParts.parse(b);

        int max = Math.max(va.numbers.length, vb.numbers.length);

        for (int i = 0; i < max; i++) {
            int ai = i < va.numbers.length ? va.numbers[i] : 0;
            int bi = i < vb.numbers.length ? vb.numbers[i] : 0;

            if (ai != bi) {
                return ai > bi ? 1 : -1;
            }
        }

        /*
         * 1.0.0 > 1.0.0-beta
         */
        if (TextUtils.isEmpty(va.suffix) && !TextUtils.isEmpty(vb.suffix)) {
            return 1;
        }

        if (!TextUtils.isEmpty(va.suffix) && TextUtils.isEmpty(vb.suffix)) {
            return -1;
        }

        return 0;
    }

    private static void showUpdateDialog(Activity activity, UpdateInfo info) {
        StringBuilder message = new StringBuilder();

        message.append("当前版本：")
                .append(info.localVersion)
                .append('\n');

        message.append("最新版本：")
                .append(info.remoteTag)
                .append('\n');

        if (info.apkSize > 0) {
            message.append("安装包大小：")
                    .append(formatSize(info.apkSize))
                    .append('\n');
        }

        if (!TextUtils.isEmpty(info.body)) {
            message.append("\n更新说明：\n")
                    .append(info.body);
        }

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(activity)
                .setTitle(info.hasApk ? "发现新版本" : "发现新版本通告")
                .setMessage(message.toString())
                .setNegativeButton("稍后再说", null);

        if (info.hasApk) {
            builder.setPositiveButton("下载更新", (dialog, which) -> openUrl(activity, info.apkDownloadUrl));
            builder.setNeutralButton("查看发布页", (dialog, which) -> openUrl(activity, info.releaseHtmlUrl));
        } else {
            builder.setPositiveButton("查看发布页", (dialog, which) -> openUrl(activity, info.releaseHtmlUrl));
        }

        builder.show();
    }

    private static void openUrl(Activity activity, String url) {
        if (activity == null || TextUtils.isEmpty(url)) {
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            activity.startActivity(intent);
        } catch (Throwable throwable) {
            Toast.makeText(activity, "无法打开链接：" + throwable.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "未知";
        }

        double mb = bytes / 1024.0d / 1024.0d;
        return String.format(Locale.US, "%.2f MB", mb);
    }

    private static class UpdateInfo {
        boolean hasNewVersion;
        boolean hasApk;
        String localVersion;
        String remoteTag;
        String releaseName;
        String body;
        String releaseHtmlUrl;
        String apkDownloadUrl;
        long apkSize;
    }

    private static class ApkAsset {
        String name;
        String browserDownloadUrl;
        long size;
    }

    private static class VersionParts {
        int[] numbers;
        String suffix;

        static VersionParts parse(String value) {
            VersionParts parts = new VersionParts();

            if (TextUtils.isEmpty(value)) {
                parts.numbers = new int[]{0};
                parts.suffix = "";
                return parts;
            }

            String normalized = value.trim().toLowerCase(Locale.US);

            String numberPart = normalized;
            String suffixPart = "";

            int dash = normalized.indexOf('-');
            if (dash >= 0) {
                numberPart = normalized.substring(0, dash);
                suffixPart = normalized.substring(dash + 1);
            }

            String[] split = numberPart.split("\\.");
            int[] numbers = new int[split.length];

            for (int i = 0; i < split.length; i++) {
                numbers[i] = parseIntSafe(split[i]);
            }

            parts.numbers = numbers;
            parts.suffix = suffixPart;
            return parts;
        }

        private static int parseIntSafe(String value) {
            try {
                if (TextUtils.isEmpty(value)) {
                    return 0;
                }

                StringBuilder digits = new StringBuilder();

                for (int i = 0; i < value.length(); i++) {
                    char c = value.charAt(i);

                    if (c >= '0' && c <= '9') {
                        digits.append(c);
                    } else {
                        break;
                    }
                }

                if (digits.length() <= 0) {
                    return 0;
                }

                return Integer.parseInt(digits.toString());
            } catch (Throwable ignored) {
                return 0;
            }
        }
    }
}