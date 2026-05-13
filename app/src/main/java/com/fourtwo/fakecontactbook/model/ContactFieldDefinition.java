package com.fourtwo.fakecontactbook.model;

import android.text.TextUtils;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.UUID;

/**
 * 通讯录扩展字段定义。
 *
 * 例子：
 * key=mobileqq       label=QQ
 * key=wechat   label=微信
 * key=alipay   label=支付宝
 *
 * 当前版本先用于宿主端编辑、展示、搜索。后续如果你找到某个平台/ROM 的 hook 点，
 * 可以直接按 key 取 FakeContact.extraFields 里的值，再映射到对应 Cursor 字段。
 */
public class ContactFieldDefinition {
    public String id;
    public String key;
    public String label;
    public String category;
    public boolean enabled;

    public ContactFieldDefinition() {
        this.id = UUID.randomUUID().toString();
        this.key = "";
        this.label = "";
        this.category = "社交平台";
        this.enabled = true;
    }

    public ContactFieldDefinition(String key, String label, String category, boolean enabled) {
        this.id = UUID.randomUUID().toString();
        this.key = normalizeKey(key);
        this.label = TextUtils.isEmpty(label) ? this.key : label.trim();
        this.category = TextUtils.isEmpty(category) ? "社交平台" : category.trim();
        this.enabled = enabled;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", TextUtils.isEmpty(id) ? UUID.randomUUID().toString() : id);
        object.put("key", normalizeKey(key));
        object.put("label", label == null ? "" : label);
        object.put("category", category == null ? "社交平台" : category);
        object.put("enabled", enabled);
        return object;
    }

    public static ContactFieldDefinition fromJson(JSONObject object) {
        ContactFieldDefinition definition = new ContactFieldDefinition();
        if (object == null) return definition;
        definition.id = object.optString("id", UUID.randomUUID().toString());
        definition.key = normalizeKey(object.optString("key", ""));
        definition.label = object.optString("label", definition.key);
        definition.category = object.optString("category", "社交平台");
        definition.enabled = object.optBoolean("enabled", true);
        return definition;
    }

    public static String normalizeKey(String raw) {
        if (raw == null) return "";
        String value = raw.trim().toLowerCase(Locale.US);
        value = value.replaceAll("[^a-z0-9_]+", "_");
        while (value.startsWith("_")) value = value.substring(1);
        while (value.endsWith("_")) value = value.substring(0, value.length() - 1);
        return value;
    }
}