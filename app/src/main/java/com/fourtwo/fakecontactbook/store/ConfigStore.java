package com.fourtwo.fakecontactbook.store;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.fourtwo.fakecontactbook.model.AppRule;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.model.FakeContact;
import com.fourtwo.fakecontactbook.model.SocialProfileInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class ConfigStore {
    public static final String MODULE_PACKAGE = "com.fourtwo.fakecontactbook";
    public static final String PROVIDER_AUTHORITY = "com.fourtwo.fakecontactbook.provider";
    public static final String PROVIDER_URI = "content://" + PROVIDER_AUTHORITY + "/config";

    public static final String SOCIAL_API_URI = "content://" + PROVIDER_AUTHORITY + "/social";
    public static final String SOCIAL_UPSERT_URI = "content://" + PROVIDER_AUTHORITY + "/social/upsert";

    public static final String METHOD_UPSERT_SOCIAL_PROFILE = "upsertSocialProfile";
    public static final String EXTRA_PAYLOAD_JSON = "payloadJson";

    private static final String PREFS = "fake_contact_book_config";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_RULES = "rules";
    private static final String KEY_GLOBAL_ENABLED = "global_enabled";
    private static final String KEY_GLOBAL_PROFILE_ID = "global_profile_id";

    private ConfigStore() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void ensureDefaultData(Context context) {
        SharedPreferences preferences = prefs(context);

        if (TextUtils.isEmpty(preferences.getString(KEY_PROFILES, ""))) {
            ContactProfile defaultProfile = new ContactProfile("默认假通讯录");
            defaultProfile.contacts.add(new FakeContact("张三", "13800138000", "zhangsan@example.com"));
            defaultProfile.contacts.add(new FakeContact("李四", "13900139000", "lisi@example.com"));
            defaultProfile.contacts.add(new FakeContact("王五", "13700137000", "wangwu@example.com"));

            ArrayList<ContactProfile> profiles = new ArrayList<>();
            profiles.add(defaultProfile);

            saveProfiles(context, profiles);
            setGlobalProfileId(context, defaultProfile.id);
        }
    }

    public static synchronized ArrayList<ContactProfile> getProfiles(Context context) {
        ensureDefaultData(context);

        String json = prefs(context).getString(KEY_PROFILES, "[]");
        ArrayList<ContactProfile> profiles = new ArrayList<>();

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) {
                    profiles.add(ContactProfile.fromJson(array.getJSONObject(i)));
                }
            }
        } catch (Exception ignored) {
        }

        return profiles;
    }

    public static synchronized void saveProfiles(Context context, ArrayList<ContactProfile> profiles) {
        JSONArray array = new JSONArray();

        if (profiles != null) {
            for (ContactProfile profile : profiles) {
                try {
                    array.put(profile.toJson());
                } catch (JSONException ignored) {
                }
            }
        }

        prefs(context).edit().putString(KEY_PROFILES, array.toString()).commit();
    }

    public static synchronized ContactProfile getProfile(Context context, String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return null;
        }

        ArrayList<ContactProfile> profiles = getProfiles(context);
        for (ContactProfile profile : profiles) {
            if (profileId.equals(profile.id)) {
                return profile;
            }
        }

        return null;
    }

    public static synchronized ContactProfile getFirstProfile(Context context) {
        ArrayList<ContactProfile> profiles = getProfiles(context);
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public static synchronized void upsertProfile(Context context, ContactProfile profile) {
        if (profile == null) return;

        ArrayList<ContactProfile> profiles = getProfiles(context);
        boolean replaced = false;

        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).id.equals(profile.id)) {
                profiles.set(i, profile);
                replaced = true;
                break;
            }
        }

        if (!replaced) {
            profiles.add(profile);
        }

        saveProfiles(context, profiles);
    }

    public static synchronized void deleteProfile(Context context, String profileId) {
        if (TextUtils.isEmpty(profileId)) return;

        ArrayList<ContactProfile> profiles = getProfiles(context);
        for (Iterator<ContactProfile> iterator = profiles.iterator(); iterator.hasNext(); ) {
            ContactProfile profile = iterator.next();
            if (profileId.equals(profile.id)) {
                iterator.remove();
            }
        }

        saveProfiles(context, profiles);

        HashMap<String, AppRule> rules = getRules(context);
        for (AppRule rule : rules.values()) {
            if (profileId.equals(rule.profileId)) {
                rule.enabled = false;
                rule.profileId = "";
            }
        }

        saveRules(context, rules);

        if (profileId.equals(getGlobalProfileId(context))) {
            ContactProfile first = getFirstProfile(context);
            setGlobalProfileId(context, first == null ? "" : first.id);
        }
    }

    public static synchronized HashMap<String, AppRule> getRules(Context context) {
        String json = prefs(context).getString(KEY_RULES, "[]");
        HashMap<String, AppRule> rules = new HashMap<>();

        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) {
                    AppRule rule = AppRule.fromJson(array.getJSONObject(i));
                    if (!TextUtils.isEmpty(rule.packageName)) {
                        rules.put(rule.packageName, rule);
                    }
                }
            }
        } catch (Exception ignored) {
        }

        return rules;
    }

    public static synchronized AppRule getRule(Context context, String packageName) {
        if (TextUtils.isEmpty(packageName)) return new AppRule();

        AppRule rule = getRules(context).get(packageName);
        if (rule == null) {
            ContactProfile first = getFirstProfile(context);
            return new AppRule(packageName, false, first == null ? "" : first.id);
        }

        return rule;
    }

    public static synchronized void setRule(Context context, AppRule rule) {
        if (rule == null || TextUtils.isEmpty(rule.packageName)) return;

        HashMap<String, AppRule> rules = getRules(context);
        rules.put(rule.packageName, rule);
        saveRules(context, rules);
    }

    public static synchronized void saveRules(Context context, HashMap<String, AppRule> rules) {
        JSONArray array = new JSONArray();

        if (rules != null) {
            for (Map.Entry<String, AppRule> entry : rules.entrySet()) {
                try {
                    array.put(entry.getValue().toJson());
                } catch (JSONException ignored) {
                }
            }
        }

        prefs(context).edit().putString(KEY_RULES, array.toString()).commit();
    }

    public static synchronized boolean isGlobalEnabled(Context context) {
        ensureDefaultData(context);
        return prefs(context).getBoolean(KEY_GLOBAL_ENABLED, false);
    }

    public static synchronized void setGlobalEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_GLOBAL_ENABLED, enabled).commit();
    }

    public static synchronized String getGlobalProfileId(Context context) {
        ensureDefaultData(context);
        return prefs(context).getString(KEY_GLOBAL_PROFILE_ID, "");
    }

    public static synchronized void setGlobalProfileId(Context context, String profileId) {
        prefs(context).edit().putString(KEY_GLOBAL_PROFILE_ID, profileId == null ? "" : profileId).commit();
    }

    public static synchronized ContactProfile resolveProfileForPackage(Context context, String packageName) {
        ensureDefaultData(context);

        if (!TextUtils.isEmpty(packageName)) {
            AppRule rule = getRules(context).get(packageName);
            if (rule != null && rule.enabled && !TextUtils.isEmpty(rule.profileId)) {
                ContactProfile profile = getProfile(context, rule.profileId);
                if (profile != null) return profile;
            }
        }

        if (isGlobalEnabled(context)) {
            String globalId = getGlobalProfileId(context);
            ContactProfile global = getProfile(context, globalId);
            if (global != null) return global;
        }

        return null;
    }

    public static synchronized int getTotalContactCount(Context context) {
        int count = 0;

        for (ContactProfile profile : getProfiles(context)) {
            if (profile.contacts != null) {
                count += profile.contacts.size();
            }
        }

        return count;
    }

    public static synchronized int getEnabledRuleCount(Context context) {
        int count = 0;

        for (AppRule rule : getRules(context).values()) {
            if (rule != null && rule.enabled) {
                count++;
            }
        }

        return count;
    }

    public static synchronized int getSocialProfileRecordCount(Context context) {
        int count = 0;

        for (ContactProfile profile : getProfiles(context)) {
            if (profile.contacts == null) continue;

            for (FakeContact contact : profile.contacts) {
                if (contact != null && contact.socialProfiles != null) {
                    count += contact.socialProfiles.size();
                }
            }
        }

        return count;
    }

    public static synchronized SocialUpsertResult upsertSocialPayload(Context context, String payloadJson) {
        if (TextUtils.isEmpty(payloadJson)) {
            return SocialUpsertResult.error("empty_payload");
        }

        try {
            return upsertSocialPayload(context, new JSONObject(payloadJson));
        } catch (Exception e) {
            return SocialUpsertResult.error("invalid_json: " + e.getMessage());
        }
    }

    public static synchronized SocialUpsertResult upsertSocialPayload(Context context, JSONObject payload) {
        ensureDefaultData(context);

        SocialProfileInfo info = SocialProfileInfo.fromPayload(payload);
        if (!info.isValid()) {
            return SocialUpsertResult.error("packageName_and_phone_required");
        }

        String normalizedPhone = info.getNormalizedPhone();
        if (TextUtils.isEmpty(normalizedPhone)) {
            return SocialUpsertResult.error("invalid_phone");
        }

        String targetProfileId = payload.optString("profileId", "");
        boolean createIfMissing = payload.optBoolean("createIfMissing", false);

        ArrayList<ContactProfile> profiles = getProfiles(context);
        int matchedCount = 0;
        boolean changed = false;

        for (ContactProfile profile : profiles) {
            if (!TextUtils.isEmpty(targetProfileId) && !targetProfileId.equals(profile.id)) {
                continue;
            }

            if (profile.contacts == null) {
                profile.contacts = new ArrayList<>();
            }

            for (FakeContact contact : profile.contacts) {
                if (contact != null && contact.samePhone(info.phone)) {
                    contact.upsertSocialProfile(info);
                    matchedCount++;
                    changed = true;
                }
            }
        }

        if (matchedCount == 0 && createIfMissing) {
            ContactProfile targetProfile = null;

            if (!TextUtils.isEmpty(targetProfileId)) {
                for (ContactProfile profile : profiles) {
                    if (targetProfileId.equals(profile.id)) {
                        targetProfile = profile;
                        break;
                    }
                }
            }

            if (targetProfile == null && !profiles.isEmpty()) {
                targetProfile = profiles.get(0);
            }

            if (targetProfile != null) {
                String displayName = payload.optString("name", "");
                if (TextUtils.isEmpty(displayName)) {
                    displayName = info.getBestNickname();
                }
                if (TextUtils.isEmpty(displayName)) {
                    displayName = info.phone;
                }

                FakeContact newContact = new FakeContact(displayName, info.phone, payload.optString("email", ""));
                newContact.upsertSocialProfile(info);

                if (targetProfile.contacts == null) {
                    targetProfile.contacts = new ArrayList<>();
                }

                targetProfile.contacts.add(newContact);

                matchedCount = 1;
                changed = true;
            }
        }

        if (changed) {
            saveProfiles(context, profiles);
        }

        SocialUpsertResult result = new SocialUpsertResult();
        result.ok = changed;
        result.reason = changed ? "ok" : "phone_not_found";
        result.packageName = info.packageName;
        result.phone = info.phone;
        result.normalizedPhone = normalizedPhone;
        result.matchedCount = matchedCount;
        return result;
    }

    public static class SocialUpsertResult {
        public boolean ok;
        public String reason;
        public String packageName;
        public String phone;
        public String normalizedPhone;
        public int matchedCount;

        public static SocialUpsertResult error(String reason) {
            SocialUpsertResult result = new SocialUpsertResult();
            result.ok = false;
            result.reason = reason;
            result.packageName = "";
            result.phone = "";
            result.normalizedPhone = "";
            result.matchedCount = 0;
            return result;
        }

        public JSONObject toJson() {
            JSONObject object = new JSONObject();

            try {
                object.put("ok", ok);
                object.put("reason", reason == null ? "" : reason);
                object.put("packageName", packageName == null ? "" : packageName);
                object.put("phone", phone == null ? "" : phone);
                object.put("normalizedPhone", normalizedPhone == null ? "" : normalizedPhone);
                object.put("matchedCount", matchedCount);
            } catch (JSONException ignored) {
            }

            return object;
        }
    }
}