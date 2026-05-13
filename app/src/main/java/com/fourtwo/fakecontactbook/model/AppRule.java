package com.fourtwo.fakecontactbook.model;

import org.json.JSONException;
import org.json.JSONObject;

public class AppRule {
    public String packageName;
    public boolean enabled;
    public String profileId;

    public AppRule() {
        this.packageName = "";
        this.enabled = false;
        this.profileId = "";
    }

    public AppRule(String packageName, boolean enabled, String profileId) {
        this.packageName = packageName == null ? "" : packageName;
        this.enabled = enabled;
        this.profileId = profileId == null ? "" : profileId;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("packageName", packageName == null ? "" : packageName);
        object.put("enabled", enabled);
        object.put("profileId", profileId == null ? "" : profileId);
        return object;
    }

    public static AppRule fromJson(JSONObject object) {
        AppRule rule = new AppRule();
        rule.packageName = object.optString("packageName", "");
        rule.enabled = object.optBoolean("enabled", false);
        rule.profileId = object.optString("profileId", "");
        return rule;
    }
}
