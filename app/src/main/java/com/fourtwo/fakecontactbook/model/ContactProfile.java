package com.fourtwo.fakecontactbook.model;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.UUID;

public class ContactProfile {
    public String id;
    public String name;
    public ArrayList<FakeContact> contacts;

    public ContactProfile() {
        this.id = UUID.randomUUID().toString();
        this.name = "未命名方案";
        this.contacts = new ArrayList<>();
    }

    public ContactProfile(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name == null || name.trim().isEmpty() ? "未命名方案" : name.trim();
        this.contacts = new ArrayList<>();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id == null ? UUID.randomUUID().toString() : id);
        object.put("name", name == null ? "未命名方案" : name);
        JSONArray array = new JSONArray();
        if (contacts != null) {
            for (FakeContact contact : contacts) {
                array.put(contact.toJson());
            }
        }
        object.put("contacts", array);
        return object;
    }

    public static ContactProfile fromJson(JSONObject object) {
        ContactProfile profile = new ContactProfile();
        profile.id = object.optString("id", UUID.randomUUID().toString());
        profile.name = object.optString("name", "未命名方案");
        profile.contacts = new ArrayList<>();
        JSONArray array = object.optJSONArray("contacts");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item != null) {
                    profile.contacts.add(FakeContact.fromJson(item));
                }
            }
        }
        return profile;
    }
}
