package com.fourtwo.fakecontactbook.model;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

public class SocialProfileInfo {
    public String packageName;
    public String phone;
    public long updatedAt;
    public JSONObject payload;

    public SocialProfileInfo() {
        this.packageName = "";
        this.phone = "";
        this.updatedAt = System.currentTimeMillis();
        this.payload = new JSONObject();
    }

    public static SocialProfileInfo fromPayload(JSONObject rawPayload) {
        SocialProfileInfo info = new SocialProfileInfo();

        if (rawPayload == null) {
            return info;
        }

        info.packageName = rawPayload.optString("packageName", "").trim();
        info.phone = rawPayload.optString("phone", "").trim();
        info.updatedAt = rawPayload.optLong("_updatedAt", System.currentTimeMillis());
        info.payload = cloneJson(rawPayload);

        try {
            info.payload.put("packageName", info.packageName);
            info.payload.put("phone", info.phone);
            info.payload.put("_updatedAt", info.updatedAt);
        } catch (JSONException ignored) {
        }

        return info;
    }

    public static SocialProfileInfo fromJson(JSONObject object) {
        SocialProfileInfo info = new SocialProfileInfo();

        if (object == null) {
            return info;
        }

        info.packageName = object.optString("packageName", "");
        info.phone = object.optString("phone", "");
        info.updatedAt = object.optLong("updatedAt", System.currentTimeMillis());

        JSONObject storedPayload = object.optJSONObject("payload");
        info.payload = storedPayload == null ? new JSONObject() : cloneJson(storedPayload);

        return info;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("packageName", packageName == null ? "" : packageName);
        object.put("phone", phone == null ? "" : phone);
        object.put("updatedAt", updatedAt);
        object.put("payload", payload == null ? new JSONObject() : payload);
        return object;
    }

    public boolean isValid() {
        return !TextUtils.isEmpty(packageName) && !TextUtils.isEmpty(phone);
    }

    public String getNormalizedPhone() {
        return FakeContact.normalizePhone(phone);
    }

    public String getAppLabel(Context context) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return packageName == null ? "" : packageName;
        }

        try {
            PackageManager pm = context.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)));
        } catch (Exception ignored) {
            return packageName;
        }
    }

    public String getBestNickname() {
        String[] keys = new String[]{
                "nickname",
                "nickName",
                "nick",
                "displayName",
                "screenName",
                "name",
                "username",
                "userName",
                "accountName",
                "socialName",
                "社交平台昵称",
                "昵称",
                "用户名",
                "名称"
        };

        for (String key : keys) {
            String value = optStringAnyKey(key);
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }

        return "";
    }

    public String getBestAccount() {
        String[] keys = new String[]{
                "account",
                "accountId",
                "userId",
                "uid",
                "uin",
                "openId",
                "unionId",
                "socialAccount",
                "社交平台账号",
                "账号",
                "用户ID"
        };

        for (String key : keys) {
            String value = optStringAnyKey(key);
            if (!TextUtils.isEmpty(value)) {
                return value;
            }
        }

        return "";
    }

    public String getPreviewTitle(Context context) {
        String nickname = getBestNickname();
        if (!TextUtils.isEmpty(nickname)) {
            return nickname;
        }

        String account = getBestAccount();
        if (!TextUtils.isEmpty(account)) {
            return account;
        }

        String appLabel = getAppLabel(context);
        if (!TextUtils.isEmpty(appLabel)) {
            return appLabel;
        }

        return packageName;
    }

    public String getAvatarBase64() {
        String[] keys = new String[]{
                "avatarBase64",
                "avatar_base64",
                "headBase64",
                "head_base64",
                "photoBase64",
                "iconBase64",
                "头像base64编码",
                "头像Base64",
                "头像"
        };

        for (String key : keys) {
            String value = optStringAnyKey(key);
            if (!TextUtils.isEmpty(value) && !value.startsWith("http://") && !value.startsWith("https://")) {
                return value;
            }
        }

        return "";
    }

    public String getAvatarUrl() {
        String[] keys = new String[]{
                "avatarUrl",
                "avatar_url",
                "avatar",
                "headImgUrl",
                "headUrl",
                "photoUrl",
                "iconUrl",
                "头像http地址",
                "头像地址",
                "头像"
        };

        for (String key : keys) {
            String value = optStringAnyKey(key);
            if (!TextUtils.isEmpty(value) && (value.startsWith("http://") || value.startsWith("https://"))) {
                return value;
            }
        }

        return "";
    }

    public Bitmap decodeAvatarBitmap() {
        String base64 = getAvatarBase64();
        if (TextUtils.isEmpty(base64)) {
            return null;
        }

        try {
            int comma = base64.indexOf(',');
            if (base64.startsWith("data:image") && comma >= 0) {
                base64 = base64.substring(comma + 1);
            }

            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    public boolean containsKeyword(Context context, String keyword) {
        if (TextUtils.isEmpty(keyword)) {
            return true;
        }

        String q = keyword.toLowerCase(Locale.US);

        if (contains(packageName, q)) return true;
        if (contains(phone, q)) return true;
        if (contains(getAppLabel(context), q)) return true;

        if (payload != null) {
            Iterator<String> iterator = payload.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                String value = payload.optString(key, "");

                if (contains(key, q) || contains(value, q)) {
                    return true;
                }
            }
        }

        return false;
    }

    private String optStringAnyKey(String wantedKey) {
        if (payload == null || TextUtils.isEmpty(wantedKey)) {
            return "";
        }

        String direct = payload.optString(wantedKey, "");
        if (!TextUtils.isEmpty(direct)) {
            return direct.trim();
        }

        Iterator<String> iterator = payload.keys();
        while (iterator.hasNext()) {
            String key = iterator.next();
            if (wantedKey.equalsIgnoreCase(key)) {
                return payload.optString(key, "").trim();
            }
        }

        return "";
    }

    private static boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.US).contains(keyword);
    }

    private static JSONObject cloneJson(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }

        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}