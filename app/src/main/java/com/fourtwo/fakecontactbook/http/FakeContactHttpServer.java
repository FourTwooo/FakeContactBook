package com.fourtwo.fakecontactbook.http;

import android.content.Context;
import android.text.TextUtils;

import com.fourtwo.fakecontactbook.generator.PhoneCodeDatabaseProvider;
import com.fourtwo.fakecontactbook.generator.PhoneGenerate;
import com.fourtwo.fakecontactbook.model.AppRule;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.model.FakeContact;
import com.fourtwo.fakecontactbook.store.ConfigStore;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class FakeContactHttpServer extends NanoHTTPD {
    private final Context context;
    private final int port;

    public FakeContactHttpServer(Context context, int port) {
        super("127.0.0.1", port);
        this.context = context.getApplicationContext();
        this.port = port;
    }

    @Override
    public void start() throws IOException {
        super.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    @Override
    public Response serve(IHTTPSession session) {
        if (session == null) {
            return json(Status.BAD_REQUEST, error("invalid_session"));
        }

        if (Method.OPTIONS.equals(session.getMethod())) {
            return withCors(newFixedLengthResponse(Status.OK, "text/plain", ""));
        }

        try {
            return route(session);
        } catch (Throwable throwable) {
            JSONObject object = error("server_error");
            put(object, "message", throwable.getMessage());
            put(object, "type", throwable.getClass().getName());
            return json(Status.INTERNAL_ERROR, object);
        }
    }

    private Response route(IHTTPSession session) throws Exception {
        Method method = session.getMethod();
        String uri = normalizeUri(session.getUri());

        if (Method.GET.equals(method) && "/".equals(uri)) {
            return json(Status.OK, docs());
        }

        if (Method.GET.equals(method) && "/health".equals(uri)) {
            JSONObject object = ok();
            put(object, "server", "FakeContactBook HTTP API");
            put(object, "port", port);
            put(object, "baseUrl", "http://127.0.0.1:" + port);
            put(object, "time", System.currentTimeMillis());
            return json(Status.OK, object);
        }

        if (Method.GET.equals(method) && "/stats".equals(uri)) {
            return json(Status.OK, stats());
        }

        if ("/profiles".equals(uri)) {
            if (Method.GET.equals(method)) {
                return json(Status.OK, profiles());
            }

            if (Method.POST.equals(method)) {
                return json(Status.OK, createProfile(readJson(session)));
            }
        }

        if (uri.startsWith("/profiles/")) {
            return handleProfileRoutes(session, method, uri);
        }

        if ("/rules".equals(uri)) {
            if (Method.GET.equals(method)) {
                return json(Status.OK, rules());
            }
        }

        if (uri.startsWith("/rules/")) {
            return handleRuleRoutes(session, method, uri);
        }

        if ("/global".equals(uri)) {
            if (Method.GET.equals(method)) {
                return json(Status.OK, globalConfig());
            }

            if (Method.PUT.equals(method) || Method.POST.equals(method)) {
                return json(Status.OK, updateGlobal(readJson(session)));
            }
        }

        if ("/social/upsert".equals(uri) && Method.POST.equals(method)) {
            ConfigStore.SocialUpsertResult result =
                    ConfigStore.upsertSocialPayload(context, readJson(session));

            JSONObject object = result.toJson();
            put(object, "ok", result.ok);

            return json(result.ok ? Status.OK : Status.BAD_REQUEST, object);
        }

        if ("/generator/phones".equals(uri)) {
            if (Method.GET.equals(method) || Method.POST.equals(method)) {
                return json(Status.OK, generatePhones(session));
            }
        }

        return json(Status.NOT_FOUND, error("not_found"));
    }

    private Response handleProfileRoutes(IHTTPSession session, Method method, String uri) throws Exception {
        String[] parts = split(uri);

        if (parts.length < 2) {
            return json(Status.NOT_FOUND, error("not_found"));
        }

        String profileId = decode(parts[1]);

        if (parts.length == 2) {
            if (Method.GET.equals(method)) {
                ContactProfile profile = ConfigStore.getProfile(context, profileId);

                if (profile == null) {
                    return json(Status.NOT_FOUND, error("profile_not_found"));
                }

                JSONObject object = ok();
                put(object, "profile", profile.toJson());
                return json(Status.OK, object);
            }

            if (Method.PUT.equals(method) || Method.POST.equals(method)) {
                return json(Status.OK, updateProfile(profileId, readJson(session)));
            }

            if (Method.DELETE.equals(method)) {
                ConfigStore.deleteProfile(context, profileId);
                JSONObject object = ok();
                put(object, "deletedProfileId", profileId);
                return json(Status.OK, object);
            }
        }

        if (parts.length >= 3 && "contacts".equals(parts[2])) {
            if (parts.length == 3) {
                if (Method.GET.equals(method)) {
                    return json(Status.OK, contacts(profileId));
                }

                if (Method.POST.equals(method)) {
                    return json(Status.OK, addContacts(profileId, readJson(session)));
                }
            }

            if (parts.length == 4) {
                String contactId = decode(parts[3]);

                if (Method.PUT.equals(method) || Method.POST.equals(method)) {
                    return json(Status.OK, updateContact(profileId, contactId, readJson(session)));
                }

                if (Method.DELETE.equals(method)) {
                    return json(Status.OK, deleteContact(profileId, contactId));
                }
            }
        }

        return json(Status.NOT_FOUND, error("not_found"));
    }

    private Response handleRuleRoutes(IHTTPSession session, Method method, String uri) throws Exception {
        String[] parts = split(uri);

        if (parts.length < 2) {
            return json(Status.NOT_FOUND, error("not_found"));
        }

        String packageName = decode(parts[1]);

        if (TextUtils.isEmpty(packageName)) {
            return json(Status.BAD_REQUEST, error("empty_package"));
        }

        if (Method.GET.equals(method)) {
            AppRule rule = ConfigStore.getRule(context, packageName);
            JSONObject object = ok();
            put(object, "rule", rule.toJson());
            return json(Status.OK, object);
        }

        if (Method.PUT.equals(method) || Method.POST.equals(method)) {
            JSONObject body = readJson(session);
            AppRule rule = ConfigStore.getRule(context, packageName);
            rule.packageName = packageName;

            if (body.has("enabled")) {
                rule.enabled = body.optBoolean("enabled", rule.enabled);
            }

            if (body.has("profileId")) {
                rule.profileId = body.optString("profileId", "");
            }

            ConfigStore.setRule(context, rule);

            JSONObject object = ok();
            put(object, "rule", rule.toJson());
            return json(Status.OK, object);
        }

        if (Method.DELETE.equals(method)) {
            HashMap<String, AppRule> rules = ConfigStore.getRules(context);
            rules.remove(packageName);
            ConfigStore.saveRules(context, rules);

            JSONObject object = ok();
            put(object, "deletedPackageName", packageName);
            return json(Status.OK, object);
        }

        return json(Status.METHOD_NOT_ALLOWED, error("method_not_allowed"));
    }

    private JSONObject docs() throws JSONException {
        JSONObject object = ok();
        put(object, "name", "FakeContactBook HTTP API");
        put(object, "baseUrl", "http://127.0.0.1:" + port);
        put(object, "defaultPort", HttpApiServerManager.DEFAULT_PORT);

        JSONArray endpoints = new JSONArray();

        endpoint(endpoints, "GET", "/", "接口文档");
        endpoint(endpoints, "GET", "/health", "服务状态");
        endpoint(endpoints, "GET", "/stats", "统计信息");

        endpoint(endpoints, "GET", "/profiles", "获取所有通讯录方案");
        endpoint(endpoints, "POST", "/profiles", "新建方案，body: {\"name\":\"测试方案\"}");
        endpoint(endpoints, "GET", "/profiles/{profileId}", "获取单个方案");
        endpoint(endpoints, "PUT", "/profiles/{profileId}", "修改方案名称，body: {\"name\":\"新名称\"}");
        endpoint(endpoints, "DELETE", "/profiles/{profileId}", "删除方案");

        endpoint(endpoints, "GET", "/profiles/{profileId}/contacts", "获取方案联系人");
        endpoint(endpoints, "POST", "/profiles/{profileId}/contacts", "新增联系人或批量联系人");
        endpoint(endpoints, "PUT", "/profiles/{profileId}/contacts/{contactId}", "修改联系人");
        endpoint(endpoints, "DELETE", "/profiles/{profileId}/contacts/{contactId}", "删除联系人");

        endpoint(endpoints, "GET", "/rules", "获取所有 App 规则");
        endpoint(endpoints, "GET", "/rules/{packageName}", "获取指定 App 规则");
        endpoint(endpoints, "PUT", "/rules/{packageName}", "设置 App 规则，body: {\"enabled\":true,\"profileId\":\"...\"}");
        endpoint(endpoints, "DELETE", "/rules/{packageName}", "删除 App 规则");

        endpoint(endpoints, "GET", "/global", "获取全局 Hook 配置");
        endpoint(endpoints, "PUT", "/global", "设置全局配置，body: {\"enabled\":true,\"profileId\":\"...\"}");

        endpoint(endpoints, "POST", "/social/upsert", "提交社交资料，body 直接使用 payload JSON");

        endpoint(endpoints, "GET", "/generator/phones?incompletePhone=1380013****&maxResults=20", "生成手机号");
        endpoint(endpoints, "POST", "/generator/phones", "生成手机号，body: {\"incompletePhone\":\"1380013****\",\"maxResults\":20}");

        put(object, "endpoints", endpoints);
        return object;
    }

    private void endpoint(JSONArray array, String method, String path, String description) throws JSONException {
        JSONObject item = new JSONObject();
        put(item, "method", method);
        put(item, "path", path);
        put(item, "description", description);
        put(item, "contentType", "application/json; charset=utf-8");

        if (path.contains("{profileId}")) {
            put(item, "pathParam_profileId", "通讯录方案 ID，可通过 GET /profiles 获取。");
        }

        if (path.contains("{contactId}")) {
            put(item, "pathParam_contactId", "联系人 ID，可通过 GET /profiles/{profileId}/contacts 获取。");
        }

        if (path.contains("{packageName}")) {
            put(item, "pathParam_packageName", "目标 App 包名，例如 com.eg.android.AlipayGphone。");
        }

        if ("POST".equals(method) || "PUT".equals(method)) {
            put(item, "body", bodyHintFor(path));
        }

        put(item, "response", responseHintFor(path));

        array.put(item);
    }

    private JSONObject bodyHintFor(String path) {
        JSONObject body = new JSONObject();

        if ("/profiles".equals(path)) {
            put(body, "name", "方案名称，可选");
            return body;
        }

        if (path.contains("/contacts") && !path.contains("{contactId}")) {
            put(body, "name", "联系人姓名，可选");
            put(body, "phone", "手机号，必填");
            put(body, "email", "邮箱，可选");
            put(body, "contacts", "也可以传数组 contacts 批量添加");
            return body;
        }

        if (path.contains("/contacts/{contactId}")) {
            put(body, "name", "联系人姓名，可选");
            put(body, "phone", "手机号，可选");
            put(body, "email", "邮箱，可选");
            return body;
        }

        if (path.contains("/rules/")) {
            put(body, "enabled", "是否开启该 App 规则，boolean");
            put(body, "profileId", "绑定的通讯录方案 ID");
            return body;
        }

        if ("/global".equals(path)) {
            put(body, "enabled", "是否开启全局 Hook，boolean");
            put(body, "profileId", "全局绑定的通讯录方案 ID");
            return body;
        }

        if ("/social/upsert".equals(path)) {
            put(body, "packageName", "社交平台 App 包名，必填");
            put(body, "phone", "手机号，必填");
            put(body, "profileId", "指定方案 ID，可选");
            put(body, "createIfMissing", "手机号不存在时是否创建联系人，可选");
            put(body, "nickname", "昵称字段示例，可自由扩展");
            put(body, "account", "账号字段示例，可自由扩展");
            put(body, "avatarBase64", "头像 Base64，可选");
            return body;
        }

        if ("/generator/phones".equals(path)) {
            put(body, "incompletePhone", "手机号模板，11 位，支持 *，必填");
            put(body, "cityName", "城市名，可空");
            put(body, "isp", "运营商：不限、移动、联通、电信");
            put(body, "maxResults", "最大生成数量，0 表示不限制");
            put(body, "useDb", "是否使用本地号段库，boolean");
            return body;
        }

        put(body, "note", "该接口无固定 Body。");
        return body;
    }

    private JSONObject responseHintFor(String path) {
        JSONObject response = new JSONObject();

        put(response, "ok", "是否成功，boolean");

        if ("/health".equals(path)) {
            put(response, "server", "服务名称");
            put(response, "port", "当前端口");
            put(response, "baseUrl", "当前 API 地址");
            put(response, "time", "当前时间戳");
            return response;
        }

        if ("/stats".equals(path)) {
            put(response, "profileCount", "通讯录方案数量");
            put(response, "contactCount", "全部联系人数量");
            put(response, "enabledRuleCount", "已启用规则数量");
            put(response, "socialProfileRecordCount", "社交资料记录数量");
            put(response, "global", "全局 Hook 配置");
            return response;
        }

        if (path.contains("/profiles") && path.contains("/contacts")) {
            put(response, "contacts", "联系人数组");
            put(response, "count", "联系人数量");
            return response;
        }

        if ("/profiles".equals(path)) {
            put(response, "profiles", "通讯录方案数组");
            put(response, "count", "方案数量");
            return response;
        }

        if (path.startsWith("/profiles/")) {
            put(response, "profile", "通讯录方案对象");
            return response;
        }

        if (path.startsWith("/rules")) {
            put(response, "rule_or_rules", "单个规则或规则数组");
            return response;
        }

        if ("/global".equals(path)) {
            put(response, "enabled", "全局 Hook 是否开启");
            put(response, "profileId", "全局绑定方案 ID");
            put(response, "profile", "当前方案对象，可能为空");
            return response;
        }

        if ("/social/upsert".equals(path)) {
            put(response, "reason", "结果原因");
            put(response, "matchedCount", "匹配联系人数量");
            put(response, "normalizedPhone", "归一化手机号");
            return response;
        }

        if (path.contains("/generator/phones")) {
            put(response, "phones", "生成的手机号数组");
            put(response, "count", "生成数量");
            return response;
        }

        return response;
    }

    private JSONObject stats() throws JSONException {
        JSONObject object = ok();

        put(object, "profileCount", ConfigStore.getProfiles(context).size());
        put(object, "contactCount", ConfigStore.getTotalContactCount(context));
        put(object, "enabledRuleCount", ConfigStore.getEnabledRuleCount(context));
        put(object, "socialProfileRecordCount", ConfigStore.getSocialProfileRecordCount(context));

        JSONObject global = new JSONObject();
        put(global, "enabled", ConfigStore.isGlobalEnabled(context));
        put(global, "profileId", ConfigStore.getGlobalProfileId(context));
        put(object, "global", global);

        return object;
    }

    private JSONObject profiles() throws JSONException {
        JSONObject object = ok();
        JSONArray array = new JSONArray();

        for (ContactProfile profile : ConfigStore.getProfiles(context)) {
            array.put(profile.toJson());
        }

        put(object, "profiles", array);
        put(object, "count", array.length());
        return object;
    }

    private JSONObject createProfile(JSONObject body) throws JSONException {
        String name = body.optString("name", "").trim();

        if (TextUtils.isEmpty(name)) {
            name = "HTTP API 新建方案";
        }

        ContactProfile profile = new ContactProfile(name);
        ConfigStore.upsertProfile(context, profile);

        JSONObject object = ok();
        put(object, "profile", profile.toJson());
        return object;
    }

    private JSONObject updateProfile(String profileId, JSONObject body) throws JSONException {
        ContactProfile profile = ConfigStore.getProfile(context, profileId);

        if (profile == null) {
            return error("profile_not_found");
        }

        if (body.has("name")) {
            String name = body.optString("name", "").trim();

            if (!TextUtils.isEmpty(name)) {
                profile.name = name;
            }
        }

        ConfigStore.upsertProfile(context, profile);

        JSONObject object = ok();
        put(object, "profile", profile.toJson());
        return object;
    }

    private JSONObject contacts(String profileId) throws JSONException {
        ContactProfile profile = ConfigStore.getProfile(context, profileId);

        if (profile == null) {
            return error("profile_not_found");
        }

        JSONArray array = new JSONArray();

        if (profile.contacts != null) {
            for (FakeContact contact : profile.contacts) {
                array.put(contact.toJson());
            }
        }

        JSONObject object = ok();
        put(object, "profileId", profileId);
        put(object, "contacts", array);
        put(object, "count", array.length());
        return object;
    }

    private JSONObject addContacts(String profileId, JSONObject body) throws JSONException {
        ContactProfile profile = ConfigStore.getProfile(context, profileId);

        if (profile == null) {
            return error("profile_not_found");
        }

        if (profile.contacts == null) {
            profile.contacts = new ArrayList<>();
        }

        int added = 0;
        JSONArray contactsArray = body.optJSONArray("contacts");

        if (contactsArray != null) {
            for (int i = 0; i < contactsArray.length(); i++) {
                JSONObject item = contactsArray.optJSONObject(i);

                if (item == null) {
                    continue;
                }

                FakeContact contact = contactFromBody(item);

                if (!TextUtils.isEmpty(contact.phone)) {
                    profile.contacts.add(contact);
                    added++;
                }
            }
        } else {
            FakeContact contact = contactFromBody(body);

            if (!TextUtils.isEmpty(contact.phone)) {
                profile.contacts.add(contact);
                added++;
            }
        }

        ConfigStore.upsertProfile(context, profile);

        JSONObject object = ok();
        put(object, "profileId", profileId);
        put(object, "added", added);
        put(object, "profile", profile.toJson());
        return object;
    }

    private JSONObject updateContact(String profileId, String contactId, JSONObject body) throws JSONException {
        ContactProfile profile = ConfigStore.getProfile(context, profileId);

        if (profile == null || profile.contacts == null) {
            return error("profile_not_found");
        }

        FakeContact target = null;

        for (FakeContact contact : profile.contacts) {
            if (contact != null && contactId.equals(contact.id)) {
                target = contact;
                break;
            }
        }

        if (target == null) {
            return error("contact_not_found");
        }

        if (body.has("name")) {
            target.name = body.optString("name", "");
        }

        if (body.has("phone")) {
            target.phone = body.optString("phone", "");
        }

        if (body.has("email")) {
            target.email = body.optString("email", "");
        }

        ConfigStore.upsertProfile(context, profile);

        JSONObject object = ok();
        put(object, "contact", target.toJson());
        return object;
    }

    private JSONObject deleteContact(String profileId, String contactId) throws JSONException {
        ContactProfile profile = ConfigStore.getProfile(context, profileId);

        if (profile == null || profile.contacts == null) {
            return error("profile_not_found");
        }

        boolean removed = false;

        for (int i = profile.contacts.size() - 1; i >= 0; i--) {
            FakeContact contact = profile.contacts.get(i);

            if (contact != null && contactId.equals(contact.id)) {
                profile.contacts.remove(i);
                removed = true;
                break;
            }
        }

        if (removed) {
            ConfigStore.upsertProfile(context, profile);
        }

        JSONObject object = ok();
        put(object, "profileId", profileId);
        put(object, "contactId", contactId);
        put(object, "removed", removed);
        return object;
    }

    private JSONObject rules() throws JSONException {
        JSONObject object = ok();
        JSONArray array = new JSONArray();

        for (Map.Entry<String, AppRule> entry : ConfigStore.getRules(context).entrySet()) {
            if (entry.getValue() != null) {
                array.put(entry.getValue().toJson());
            }
        }

        put(object, "rules", array);
        put(object, "count", array.length());
        return object;
    }

    private JSONObject globalConfig() throws JSONException {
        JSONObject object = ok();
        put(object, "enabled", ConfigStore.isGlobalEnabled(context));
        put(object, "profileId", ConfigStore.getGlobalProfileId(context));

        ContactProfile profile = ConfigStore.getProfile(context, ConfigStore.getGlobalProfileId(context));
        if (profile != null) {
            put(object, "profile", profile.toJson());
        }

        return object;
    }

    private JSONObject updateGlobal(JSONObject body) throws JSONException {
        if (body.has("enabled")) {
            ConfigStore.setGlobalEnabled(context, body.optBoolean("enabled", false));
        }

        if (body.has("profileId")) {
            ConfigStore.setGlobalProfileId(context, body.optString("profileId", ""));
        }

        return globalConfig();
    }

    private JSONObject generatePhones(IHTTPSession session) throws Exception {
        JSONObject body = Method.POST.equals(session.getMethod()) ? readJson(session) : new JSONObject();
        Map<String, String> params = session.getParms();

        String incompletePhone = firstNonEmpty(
                body.optString("incompletePhone", ""),
                params.get("incompletePhone")
        );

        String cityName = firstNonEmpty(
                body.optString("cityName", ""),
                params.get("cityName")
        );

        String isp = firstNonEmpty(
                body.optString("isp", ""),
                params.get("isp")
        );

        if (TextUtils.isEmpty(isp)) {
            isp = "不限";
        }

        int maxResults = body.has("maxResults")
                ? body.optInt("maxResults", 0)
                : parseInt(params.get("maxResults"), 0);

        boolean useDb = body.has("useDb")
                ? body.optBoolean("useDb", false)
                : parseBool(params.get("useDb"), false);

        PhoneGenerate generator = new PhoneGenerate();
        generator.isDb = useDb;

        if (useDb) {
            generator.setDbProvider(new PhoneCodeDatabaseProvider(context));
        }

        ArrayList<String> phones = generator.getPhone(
                incompletePhone,
                cityName,
                isp,
                maxResults
        );

        JSONArray array = new JSONArray();

        for (String phone : phones) {
            array.put(phone);
        }

        JSONObject object = ok();
        put(object, "count", array.length());
        put(object, "phones", array);
        put(object, "maxResults", maxResults);
        put(object, "useDb", useDb);
        return object;
    }

    private FakeContact contactFromBody(JSONObject body) {
        FakeContact contact = new FakeContact();
        contact.name = body.optString("name", "");
        contact.phone = body.optString("phone", "");
        contact.email = body.optString("email", "");
        return contact;
    }

    private JSONObject readJson(IHTTPSession session) throws Exception {
        String body = readBody(session);

        if (TextUtils.isEmpty(body)) {
            return new JSONObject();
        }

        return new JSONObject(body);
    }

    private String readBody(IHTTPSession session) throws Exception {
        HashMap<String, String> files = new HashMap<>();
        session.parseBody(files);

        String body = files.get("postData");

        return body == null ? "" : body.trim();
    }

    private String normalizeUri(String uri) {
        if (TextUtils.isEmpty(uri)) {
            return "/";
        }

        String value = uri.trim();

        if (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }

        return value;
    }

    private String[] split(String uri) {
        String value = normalizeUri(uri);

        if (value.startsWith("/")) {
            value = value.substring(1);
        }

        if (TextUtils.isEmpty(value)) {
            return new String[0];
        }

        return value.split("/");
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8.name());
        } catch (Exception ignored) {
            return value == null ? "" : value;
        }
    }

    private String firstNonEmpty(String a, String b) {
        if (!TextUtils.isEmpty(a)) {
            return a;
        }

        return b == null ? "" : b;
    }

    private int parseInt(String value, int fallback) {
        try {
            if (TextUtils.isEmpty(value)) {
                return fallback;
            }

            return Integer.parseInt(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private boolean parseBool(String value, boolean fallback) {
        if (TextUtils.isEmpty(value)) {
            return fallback;
        }

        return "1".equals(value)
                || "true".equalsIgnoreCase(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value);
    }

    private JSONObject ok() {
        JSONObject object = new JSONObject();
        put(object, "ok", true);
        return object;
    }

    private JSONObject error(String reason) {
        JSONObject object = new JSONObject();
        put(object, "ok", false);
        put(object, "reason", reason);
        return object;
    }

    private void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException ignored) {
        }
    }

    private Response json(Status status, JSONObject object) {
        Response response = newFixedLengthResponse(
                status,
                "application/json; charset=utf-8",
                object == null ? "{}" : object.toString()
        );

        return withCors(response);
    }

    private Response withCors(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
        return response;
    }
}