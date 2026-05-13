package com.fourtwo.fakecontactbook.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import android.text.TextUtils;

import com.fourtwo.fakecontactbook.model.AppRule;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.store.ConfigStore;

import org.json.JSONObject;

import java.util.Arrays;

public class HookConfigProvider extends ContentProvider {
    private static final int MATCH_CONFIG = 1;
    private static final int MATCH_SOCIAL = 2;
    private static final int MATCH_SOCIAL_UPSERT = 3;

    private void addConfigRow(
            MatrixCursor cursor,
            boolean enabled,
            String profileJson,
            String reason,
            String mode,
            boolean explicit
    ) {
        cursor.addRow(new Object[]{
                enabled ? 1 : 0,
                profileJson == null ? "" : profileJson,
                reason == null ? "" : reason,
                mode == null ? "disabled" : mode,
                explicit ? 1 : 0
        });
    }

    private ResolveResult resolveProfileForQuery(Context context, String packageName) {
        ResolveResult result = new ResolveResult();

        if (context == null || TextUtils.isEmpty(packageName)) {
            return result;
        }

        /*
         * 第一优先级：显式 App 规则。
         */
        AppRule rule = ConfigStore.getRules(context).get(packageName);

        if (rule != null && rule.enabled && !TextUtils.isEmpty(rule.profileId)) {
            ContactProfile profile = ConfigStore.getProfile(context, rule.profileId);

            if (profile != null) {
                result.profile = profile;
                result.mode = "rule";
                result.explicit = true;
                return result;
            }
        }

        /*
         * 第二优先级：全局规则。
         */
        if (ConfigStore.isGlobalEnabled(context)) {
            ContactProfile profile = ConfigStore.getProfile(
                    context,
                    ConfigStore.getGlobalProfileId(context)
            );

            if (profile != null) {
                result.profile = profile;
                result.mode = "global";
                result.explicit = false;
                return result;
            }
        }

        return result;
    }

    private static class ResolveResult {
        ContactProfile profile;
        String mode = "disabled";
        boolean explicit = false;
    }
    private static final String CONTACTS_PROVIDER_PACKAGE = "com.android.providers.contacts";

    private final UriMatcher matcher = new UriMatcher(UriMatcher.NO_MATCH);

    public HookConfigProvider() {
        matcher.addURI(ConfigStore.PROVIDER_AUTHORITY, "config", MATCH_CONFIG);
        matcher.addURI(ConfigStore.PROVIDER_AUTHORITY, "social", MATCH_SOCIAL);
        matcher.addURI(ConfigStore.PROVIDER_AUTHORITY, "social/upsert", MATCH_SOCIAL_UPSERT);
    }

    @Override
    public boolean onCreate() {
        if (getContext() != null) {
            ConfigStore.ensureDefaultData(getContext());
        }
        return true;
    }

    /**
     * 这个 query 是你现在 Hook 读取假通讯录配置用的，不要删。
     */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (matcher.match(uri) != MATCH_CONFIG) {
            return null;
        }

        MatrixCursor cursor = new MatrixCursor(new String[]{
                "enabled",
                "profile_json",
                "reason",
                "mode",
                "explicit"
        });

        Context context = getContext();

        if (context == null) {
            addConfigRow(cursor, false, "", "no_context", "disabled", false);
            return cursor;
        }

        String targetPackage = uri.getQueryParameter("pkg");
        if (TextUtils.isEmpty(targetPackage) && selectionArgs != null && selectionArgs.length > 0) {
            targetPackage = selectionArgs[0];
        }

        if (TextUtils.isEmpty(targetPackage)) {
            addConfigRow(cursor, false, "", "empty_package", "disabled", false);
            return cursor;
        }

        if (!callerAllowedForConfig(context, targetPackage)) {
            addConfigRow(cursor, false, "", "caller_not_allowed", "disabled", false);
            return cursor;
        }

        ResolveResult resolved = resolveProfileForQuery(context, targetPackage);

        if (resolved.profile == null) {
            addConfigRow(cursor, false, "", "disabled", "disabled", false);
            return cursor;
        }

        try {
            addConfigRow(
                    cursor,
                    true,
                    resolved.profile.toJson().toString(),
                    "ok",
                    resolved.mode,
                    resolved.explicit
            );
        } catch (Exception e) {
            addConfigRow(cursor, false, "", "json_error", "disabled", false);
        }

        return cursor;
    }

    /**
     * 推荐给 Xposed Hook 端使用这个方式提交第三方平台资料。
     *
     * context.getContentResolver().call(
     *      Uri.parse("content://com.fourtwo.fakecontactbook.provider/social"),
     *      "upsertSocialProfile",
     *      null,
     *      bundle
     * );
     */
    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Bundle resultBundle = new Bundle();

        Context context = getContext();
        if (context == null) {
            putResult(resultBundle, ConfigStore.SocialUpsertResult.error("no_context"));
            return resultBundle;
        }

        if (!ConfigStore.METHOD_UPSERT_SOCIAL_PROFILE.equals(method)) {
            putResult(resultBundle, ConfigStore.SocialUpsertResult.error("unknown_method"));
            return resultBundle;
        }

        String payloadJson = null;

        if (extras != null) {
            payloadJson = extras.getString(ConfigStore.EXTRA_PAYLOAD_JSON);
        }

        if (TextUtils.isEmpty(payloadJson)) {
            payloadJson = arg;
        }

        JSONObject payload;
        try {
            payload = new JSONObject(payloadJson);
        } catch (Exception e) {
            putResult(resultBundle, ConfigStore.SocialUpsertResult.error("invalid_json: " + e.getMessage()));
            return resultBundle;
        }

        String platformPackage = payload.optString("packageName", "").trim();
        if (!callerAllowedForSocialSubmit(context, platformPackage)) {
            putResult(resultBundle, ConfigStore.SocialUpsertResult.error("caller_not_allowed"));
            return resultBundle;
        }

        ConfigStore.SocialUpsertResult result = ConfigStore.upsertSocialPayload(context, payload);
        putResult(resultBundle, result);
        return resultBundle;
    }

    /**
     * 兼容另一种写法：
     *
     * ContentValues values = new ContentValues();
     * values.put("payload_json", payload.toString());
     * resolver.insert(Uri.parse("content://com.fourtwo.fakecontactbook.provider/social/upsert"), values);
     */
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        if (matcher.match(uri) != MATCH_SOCIAL_UPSERT) {
            throw new UnsupportedOperationException("unsupported insert uri: " + uri);
        }

        Context context = getContext();
        if (context == null) {
            return null;
        }

        String payloadJson = values == null ? "" : values.getAsString("payload_json");

        JSONObject payload;
        try {
            payload = new JSONObject(payloadJson);
        } catch (Exception ignored) {
            return null;
        }

        String platformPackage = payload.optString("packageName", "").trim();
        if (!callerAllowedForSocialSubmit(context, platformPackage)) {
            return null;
        }

        ConfigStore.SocialUpsertResult result = ConfigStore.upsertSocialPayload(context, payload);
        if (!result.ok) {
            return null;
        }

        return Uri.parse(
                ConfigStore.SOCIAL_UPSERT_URI
                        + "/"
                        + Uri.encode(result.packageName)
                        + "/"
                        + Uri.encode(result.normalizedPhone)
        );
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("delete unsupported");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("update unsupported");
    }

    @Override
    public String getType(Uri uri) {
        int match = matcher.match(uri);
        if (match == MATCH_CONFIG) {
            return "vnd.android.cursor.item/vnd.fakecontactbook.config";
        }
        if (match == MATCH_SOCIAL || match == MATCH_SOCIAL_UPSERT) {
            return "vnd.android.cursor.item/vnd.fakecontactbook.social";
        }
        return null;
    }

    private void putResult(Bundle bundle, ConfigStore.SocialUpsertResult result) {
        bundle.putBoolean("ok", result.ok);
        bundle.putString("reason", result.reason == null ? "" : result.reason);
        bundle.putString("packageName", result.packageName == null ? "" : result.packageName);
        bundle.putString("phone", result.phone == null ? "" : result.phone);
        bundle.putString("normalizedPhone", result.normalizedPhone == null ? "" : result.normalizedPhone);
        bundle.putInt("matchedCount", result.matchedCount);
        bundle.putString("resultJson", result.toJson().toString());
    }

    private boolean callerAllowedForConfig(Context context, String targetPackage) {
        int callingUid = Binder.getCallingUid();

        if (callingUid == Process.myUid()) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        if (packages == null) return false;

        if (Arrays.asList(packages).contains(targetPackage)) {
            return true;
        }

        return Arrays.asList(packages).contains(CONTACTS_PROVIDER_PACKAGE);
    }

    private boolean callerAllowedForSocialSubmit(Context context, String platformPackage) {
        if (TextUtils.isEmpty(platformPackage)) {
            return false;
        }

        int callingUid = Binder.getCallingUid();

        if (callingUid == Process.myUid()) {
            return true;
        }

        PackageManager pm = context.getPackageManager();
        String[] packages = pm.getPackagesForUid(callingUid);
        if (packages == null) return false;

        /*
         * 被 Hook App 提交社交平台数据时：
         * payload.packageName 必须等于当前调用方 UID 下面的某个包名。
         *
         * 这样可以避免普通 App 冒充 QQ/微信/支付宝写入资料。
         */
        return Arrays.asList(packages).contains(platformPackage);
    }
}