package com.fourtwo.fakecontactbook.model;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

public class FakeContact {
    public String id;
    public String name;
    public String phone;
    public String email;

    /**
     * key = 社交平台 App 包名。
     *
     * 例如：
     * com.tencent.mobileqq
     * com.tencent.mm
     * com.eg.android.AlipayGphone
     */
    public HashMap<String, SocialProfileInfo> socialProfiles;

    public FakeContact() {
        this.id = UUID.randomUUID().toString();
        this.name = "";
        this.phone = "";
        this.email = "";
        this.socialProfiles = new HashMap<>();
    }

    public FakeContact(String name, String phone, String email) {
        this.id = UUID.randomUUID().toString();
        this.name = name == null ? "" : name.trim();
        this.phone = phone == null ? "" : phone.trim();
        this.email = email == null ? "" : email.trim();
        this.socialProfiles = new HashMap<>();
    }

    public static String normalizePhone(String rawPhone) {
        if (rawPhone == null) return "";

        String value = rawPhone.trim();
        value = value.replace(" ", "");
        value = value.replace("-", "");
        value = value.replace("(", "");
        value = value.replace(")", "");

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') {
                builder.append(c);
            }
        }

        value = builder.toString();

        // 国内常见 +86 / 86 归一化。
        if (value.startsWith("86") && value.length() == 13) {
            value = value.substring(2);
        }

        return value;
    }

    public String getNormalizedPhone() {
        return normalizePhone(phone);
    }

    public boolean samePhone(String otherPhone) {
        String a = getNormalizedPhone();
        String b = normalizePhone(otherPhone);
        return !TextUtils.isEmpty(a) && a.equals(b);
    }

    public void upsertSocialProfile(SocialProfileInfo info) {
        if (info == null || !info.isValid()) {
            return;
        }

        if (socialProfiles == null) {
            socialProfiles = new HashMap<>();
        }

        long now = System.currentTimeMillis();

        /*
         * 同一个平台 App 多次提交同一个手机号的数据时，不能整包覆盖。
         *
         * 例如：
         * 第一次：nickname + account
         * 第二次：avatarBase64
         *
         * 最终应该是：
         * nickname + account + avatarBase64
         */
        SocialProfileInfo stored = socialProfiles.get(info.packageName);

        if (stored == null) {
            stored = new SocialProfileInfo();
            stored.packageName = info.packageName;
            stored.phone = info.phone;
            stored.updatedAt = now;
            stored.payload = new JSONObject();
        }

        JSONObject mergedPayload = cloneJsonObject(stored.payload);

        /*
         * 新 payload 浅合并到旧 payload。
         * 同名字段：新值覆盖旧值。
         * 未提交字段：保留旧值。
         */
        mergeJsonObject(mergedPayload, info.payload);

        try {
            mergedPayload.put("packageName", info.packageName);
            mergedPayload.put("phone", info.phone);
            mergedPayload.put("_updatedAt", now);
        } catch (JSONException ignored) {
        }

        stored.packageName = info.packageName;
        stored.phone = info.phone;
        stored.updatedAt = now;
        stored.payload = mergedPayload;

        socialProfiles.put(info.packageName, stored);
    }

    private static JSONObject cloneJsonObject(JSONObject source) {
        if (source == null) {
            return new JSONObject();
        }

        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void mergeJsonObject(JSONObject target, JSONObject patch) {
        if (target == null || patch == null) {
            return;
        }

        Iterator<String> iterator = patch.keys();

        while (iterator.hasNext()) {
            String key = iterator.next();

            if (TextUtils.isEmpty(key)) {
                continue;
            }

            Object value = patch.opt(key);

            /*
             * 不让 null 把旧字段清掉。
             * 你的 SocialProfileSubmitter 本身也跳过 null 字段，这里是多一道保险。
             */
            if (value == null || value == JSONObject.NULL) {
                continue;
            }

            try {
                target.put(key, value);
            } catch (Exception ignored) {
            }
        }
    }
    public ArrayList<SocialProfileInfo> getSortedSocialProfiles() {
        ArrayList<SocialProfileInfo> result = new ArrayList<>();

        if (socialProfiles != null) {
            result.addAll(socialProfiles.values());
        }

        result.sort((a, b) -> Long.compare(b.updatedAt, a.updatedAt));
        return result;
    }

    public boolean matches(Context context, String fieldKey, String keyword) {
        String q = keyword == null ? "" : keyword.trim().toLowerCase(Locale.US);
        if (q.isEmpty()) return true;

        String key = fieldKey == null ? "all" : fieldKey.trim().toLowerCase(Locale.US);

        if ("name".equals(key)) return contains(name, q);
        if ("phone".equals(key)) return contains(phone, q) || contains(getNormalizedPhone(), q);
        if ("email".equals(key)) return contains(email, q);

        if ("social".equals(key)) {
            return socialContains(context, q);
        }

        if (contains(name, q)) return true;
        if (contains(phone, q)) return true;
        if (contains(getNormalizedPhone(), q)) return true;
        if (contains(email, q)) return true;

        return socialContains(context, q);
    }

    private boolean socialContains(Context context, String q) {
        if (socialProfiles == null || socialProfiles.isEmpty()) {
            return false;
        }

        for (SocialProfileInfo info : socialProfiles.values()) {
            if (info != null && info.containsKeyword(context, q)) {
                return true;
            }
        }

        return false;
    }

    private boolean contains(String value, String q) {
        return value != null && value.toLowerCase(Locale.US).contains(q);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id == null ? UUID.randomUUID().toString() : id);
        object.put("name", name == null ? "" : name);
        object.put("phone", phone == null ? "" : phone);
        object.put("email", email == null ? "" : email);

        JSONArray socialArray = new JSONArray();
        if (socialProfiles != null) {
            for (SocialProfileInfo info : socialProfiles.values()) {
                if (info != null && info.isValid()) {
                    socialArray.put(info.toJson());
                }
            }
        }

        object.put("socialProfiles", socialArray);
        return object;
    }

    public static FakeContact fromJson(JSONObject object) {
        FakeContact contact = new FakeContact();

        if (object == null) {
            return contact;
        }

        contact.id = object.optString("id", UUID.randomUUID().toString());
        contact.name = object.optString("name", "");
        contact.phone = object.optString("phone", "");
        contact.email = object.optString("email", "");
        contact.socialProfiles = new HashMap<>();

        JSONArray socialArray = object.optJSONArray("socialProfiles");
        if (socialArray != null) {
            for (int i = 0; i < socialArray.length(); i++) {
                JSONObject item = socialArray.optJSONObject(i);
                SocialProfileInfo info = SocialProfileInfo.fromJson(item);
                if (info.isValid()) {
                    contact.socialProfiles.put(info.packageName, info);
                }
            }
        }

        /*
         * 兼容上一版我给你的 extraFields 结构。
         * 旧数据不会报错，只是不再作为宿主端字段管理使用。
         */
        JSONObject oldExtras = object.optJSONObject("extraFields");
        if (oldExtras != null && oldExtras.length() > 0) {
            try {
                JSONObject legacyPayload = new JSONObject();
                legacyPayload.put("packageName", "legacy.extra.fields");
                legacyPayload.put("phone", contact.phone);
                legacyPayload.put("_source", "旧版宿主端 extraFields");

                Iterator<String> iterator = oldExtras.keys();
                while (iterator.hasNext()) {
                    String k = iterator.next();
                    legacyPayload.put(k, oldExtras.optString(k, ""));
                }

                SocialProfileInfo legacy = SocialProfileInfo.fromPayload(legacyPayload);
                if (legacy.isValid()) {
                    contact.socialProfiles.put(legacy.packageName, legacy);
                }
            } catch (Exception ignored) {
            }
        }

        return contact;
    }
}