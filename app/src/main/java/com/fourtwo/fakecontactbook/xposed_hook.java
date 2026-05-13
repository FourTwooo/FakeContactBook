package com.fourtwo.fakecontactbook;

import android.app.Application;
import android.content.ContentProvider;
import android.content.Context;
import android.database.AbstractCursor;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;

import com.fourtwo.fakecontactbook.xposedmodels.ModelsHooks;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class xposed_hook implements IXposedHookLoadPackage {
    private static final String MODULE_PACKAGE = "com.fourtwo.fakecontactbook";
    private static final String PROVIDER_AUTHORITY = "com.fourtwo.fakecontactbook.provider";
    private static final String PROVIDER_URI = "content://" + PROVIDER_AUTHORITY + "/config";

    private static final String CONTACTS_PROVIDER_PACKAGE = "com.android.providers.contacts";
    private static final String CONTACTS_PROVIDER_CLASS = "com.android.providers.contacts.ContactsProvider2";
    private static final String CONTENT_PROVIDER_TRANSPORT_CLASS = "android.content.ContentProvider$Transport";

    private static final Object HOOK_LOCK = new Object();
    private static volatile boolean hooked = false;

    private static final String EXTRA_ADDRESS_BOOK_INDEX =
            "android.provider.extra.ADDRESS_BOOK_INDEX";
    private static final String EXTRA_ADDRESS_BOOK_INDEX_TITLES =
            "android.provider.extra.ADDRESS_BOOK_INDEX_TITLES";
    private static final String EXTRA_ADDRESS_BOOK_INDEX_COUNTS =
            "android.provider.extra.ADDRESS_BOOK_INDEX_COUNTS";

    /*
     * 真实通讯录白名单。
     *
     * 注意：
     * 这不是“系统 App 白名单”。
     * 只有这些包可以在全局模式下看到真实通讯录。
     *
     * 如果用户显式给这些包开启 App 规则，显式规则优先，会返回假联系人。
     */
    private static final Set<String> REAL_CONTACTS_ALLOWLIST = new HashSet<>(Arrays.asList(
            "com.android.contacts",
            "com.android.dialer",
            "com.google.android.contacts",
            "com.google.android.dialer",

            "com.oneplus.contacts",
            "com.oplus.contacts",
            "com.coloros.contacts",
            "com.heytap.contacts",

            "com.miui.contacts",
            "com.huawei.contacts",
            "com.hihonor.contacts",
            "com.vivo.contacts",
            "com.bbk.contacts",
            "com.samsung.android.contacts",
            "com.samsung.android.dialer"
    ));

    private static final Set<String> LOG_DEDUP =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam == null) {
            return;
        }

        ModelsHooks.handleLoadPackage(loadPackageParam);

        if (!CONTACTS_PROVIDER_PACKAGE.equals(loadPackageParam.packageName)) {
            return;
        }

        installProviderHooks(loadPackageParam.classLoader);
    }

    private static void installProviderHooks(ClassLoader classLoader) {
        synchronized (HOOK_LOCK) {
            if (hooked) {
                return;
            }
            hooked = true;
        }

        XC_MethodHook queryHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    int callerUid = Binder.getCallingUid();
                    param.setObjectExtra("fcb_caller_uid", callerUid);

                    Uri uri = firstUri(param.args);
                    if (!isProviderHookProbeUri(uri)) {
                        return;
                    }

                    Context context = resolveContext(param.thisObject);
                    if (context == null) {
                        return;
                    }

                    String[] callerPackages = resolveCallerPackages(context, callerUid, param.args);

                    /*
                     * 只有宿主 App 自己可以发起探针。
                     * 其他 App 即使拼了 _fcb_provider_probe=1，也不会拿到探针结果。
                     */
                    if (!containsPackage(callerPackages, MODULE_PACKAGE)) {
                        return;
                    }

                    param.setResult(buildProviderHookProbeCursor());
                } catch (Throwable throwable) {
                    log("provider hook probe error: " + throwable);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.getResult() instanceof FakeContactBookCursor) {
                        return;
                    }

                    Uri uri = firstUri(param.args);
                    if (!isContactsUri(uri)) {
                        return;
                    }

                    if (isProviderHookProbeUri(uri)) {
                        return;
                    }

                    Context context = resolveContext(param.thisObject);
                    if (context == null) {
                        logOnce("no_context", "skip contacts query: context is null, uri=" + uri);
                        return;
                    }

                    int callerUid = getCallerUid(param);
                    String[] callerPackages = resolveCallerPackages(context, callerUid, param.args);

                    if (callerPackages == null || callerPackages.length == 0) {
                        return;
                    }

                    /*
                     * 诊断测试专用：
                     * 只有宿主 App 自己查询通讯录时，才允许通过 URI 参数模拟目标包名。
                     *
                     * 例如：
                     * content://com.android.contacts/data/phones?_fcb_test_pkg=com.eg.android.AlipayGphone
                     *
                     * 其他 App 即使伪造 _fcb_test_pkg，也不会生效。
                     */
                    String trustedTestPackage = resolveTrustedTestPackage(callerPackages, uri);
                    if (!TextUtils.isEmpty(trustedTestPackage)) {
                        callerPackages = new String[]{trustedTestPackage};
                    }

                    if (containsOnlySelfOrProvider(callerPackages)) {
                        return;
                    }
                    /*
                     * 第一优先级：显式 App 规则。
                     * 命中显式规则时，返回配置的假联系人。
                     */
                    HookConfig explicitConfig = resolveConfig(context, callerPackages, true);
                    if (explicitConfig.enabled && explicitConfig.explicitRule) {
                        Cursor original = param.getResult() instanceof Cursor
                                ? (Cursor) param.getResult()
                                : null;

                        Cursor replacement = FakeContactsCursorFactory.build(
                                uri,
                                QueryParts.from(param.args),
                                explicitConfig.contacts,
                                original
                        );

                        logReplaceOnce("fake", callerPackages, uri, explicitConfig.contacts.size());
                        param.setResult(replacement);
                        return;
                    }

                    /*
                     * 第二优先级：真实通讯录白名单。
                     * 注意：只有没有显式 App 规则时才放行。
                     */
                    if (isRealContactsAllowlisted(callerPackages)) {
                        return;
                    }

                    /*
                     * 第三优先级：全局规则。
                     *
                     * 开启全局后：
                     * REAL_CONTACTS_ALLOWLIST 之外的所有 App，
                     * 都返回首页“全局 Hook”所选择方案里的假联系人。
                     */
                    HookConfig globalConfig = resolveConfig(context, callerPackages, false);
                    if (globalConfig.enabled) {
                        Cursor original = param.getResult() instanceof Cursor
                                ? (Cursor) param.getResult()
                                : null;

                        Cursor replacement = FakeContactsCursorFactory.build(
                                uri,
                                QueryParts.from(param.args),
                                globalConfig.contacts,
                                original
                        );

                        logReplaceOnce("fake_global", callerPackages, uri, globalConfig.contacts.size());
                        param.setResult(replacement);
                    }
                } catch (Throwable throwable) {
                    log("after query hook error: " + throwable);
                }
            }
        };

        int transportHookCount = hookAllQueryMethods(CONTENT_PROVIDER_TRANSPORT_CLASS, null, queryHook);
        int providerHookCount = hookAllQueryMethods(CONTACTS_PROVIDER_CLASS, classLoader, queryHook);

        if (providerHookCount == 0) {
            providerHookCount = hookAllQueryMethods(CONTACTS_PROVIDER_CLASS, null, queryHook);
        }

        log("Provider-only mixed mode installed. transportHooks="
                + transportHookCount
                + ", providerHooks="
                + providerHookCount);
    }

    private static int hookAllQueryMethods(String className, ClassLoader classLoader, XC_MethodHook hook) {
        Class<?> clazz = findClass(className, classLoader);
        if (clazz == null) {
            return 0;
        }

        try {
            Set<XC_MethodHook.Unhook> hooks = XposedBridge.hookAllMethods(clazz, "query", hook);
            return hooks == null ? 0 : hooks.size();
        } catch (Throwable throwable) {
            log("hookAllMethods failed: " + className + "#query, error=" + throwable);
            return 0;
        }
    }

    private static HookConfig resolveConfig(Context context, String[] packages, boolean explicitOnly) {
        if (packages == null) {
            return HookConfig.disabled();
        }

        for (String pkg : packages) {
            if (TextUtils.isEmpty(pkg)) {
                continue;
            }

            if (MODULE_PACKAGE.equals(pkg) || CONTACTS_PROVIDER_PACKAGE.equals(pkg)) {
                continue;
            }

            HookConfig config = HookConfigBridge.get(context, pkg);

            if (!config.enabled) {
                continue;
            }

            if (explicitOnly && config.explicitRule) {
                return config;
            }

            if (!explicitOnly && !config.explicitRule) {
                return config;
            }
        }

        return HookConfig.disabled();
    }

    private static boolean isContactsUri(Uri uri) {
        return uri != null && ContactsContract.AUTHORITY.equals(uri.getAuthority());
    }

    private static Uri firstUri(Object[] args) {
        if (args == null) {
            return null;
        }

        for (Object arg : args) {
            if (arg instanceof Uri) {
                return (Uri) arg;
            }
        }

        return null;
    }

    private static int getCallerUid(XC_MethodHook.MethodHookParam param) {
        try {
            Object value = param.getObjectExtra("fcb_caller_uid");
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            return Binder.getCallingUid();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static String[] resolveCallerPackages(Context context, int uid, Object[] args) {
        String[] packages = null;

        try {
            if (uid > 0) {
                packages = context.getPackageManager().getPackagesForUid(uid);
            }
        } catch (Throwable ignored) {
        }

        if (packages == null || packages.length == 0) {
            if (uid == 2000) {
                packages = new String[]{"com.android.shell"};
            } else if (uid == 0) {
                packages = new String[]{"root"};
            } else if (uid > 0) {
                packages = new String[]{"uid:" + uid};
            } else {
                packages = new String[]{"unknown"};
            }
        }

        String explicitCaller = extractExplicitCallerPackage(args, packages);
        if (!TextUtils.isEmpty(explicitCaller)) {
            return new String[]{explicitCaller};
        }

        return packages;
    }

    private static String extractExplicitCallerPackage(Object[] args, String[] packagesForUid) {
        if (args == null || packagesForUid == null || packagesForUid.length == 0) {
            return "";
        }

        for (Object arg : args) {
            String pkg = extractPackageNameFromArg(arg);
            if (TextUtils.isEmpty(pkg)) {
                continue;
            }

            for (String uidPkg : packagesForUid) {
                if (pkg.equals(uidPkg)) {
                    return pkg;
                }
            }
        }

        return "";
    }

    private static String extractPackageNameFromArg(Object arg) {
        if (arg == null) {
            return "";
        }

        if (arg instanceof String) {
            String value = ((String) arg).trim();

            if (looksLikePackageName(value)) {
                return value;
            }

            return "";
        }

        try {
            Method method = arg.getClass().getMethod("getPackageName");
            method.setAccessible(true);
            Object value = method.invoke(arg);

            if (value instanceof String && looksLikePackageName((String) value)) {
                return (String) value;
            }
        } catch (Throwable ignored) {
        }

        return "";
    }

    private static boolean looksLikePackageName(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }

        if (!value.contains(".")) {
            return false;
        }

        return !value.contains(" ") && !value.contains("=") && !value.contains("?");
    }

    private static boolean containsOnlySelfOrProvider(String[] packages) {
        if (packages == null || packages.length == 0) {
            return false;
        }

        for (String pkg : packages) {
            if (TextUtils.isEmpty(pkg)) {
                continue;
            }

            if (!MODULE_PACKAGE.equals(pkg) && !CONTACTS_PROVIDER_PACKAGE.equals(pkg)) {
                return false;
            }
        }

        return true;
    }

    private static boolean isRealContactsAllowlisted(String[] packages) {
        if (packages == null || packages.length == 0) {
            return false;
        }

        boolean hasAllowlisted = false;

        for (String pkg : packages) {
            if (TextUtils.isEmpty(pkg)
                    || MODULE_PACKAGE.equals(pkg)
                    || CONTACTS_PROVIDER_PACKAGE.equals(pkg)) {
                continue;
            }

            if (!REAL_CONTACTS_ALLOWLIST.contains(pkg)) {
                return false;
            }

            hasAllowlisted = true;
        }

        return hasAllowlisted;
    }

    private static Context resolveContext(Object thisObject) {
        Context context = contextFromProviderLikeObject(thisObject);
        if (context != null) {
            return context;
        }

        Application app = currentApplicationByReflection();
        if (app != null) {
            return app;
        }

        return null;
    }

    private static Context contextFromProviderLikeObject(Object object) {
        if (object == null) {
            return null;
        }

        if (object instanceof ContentProvider) {
            try {
                Context context = ((ContentProvider) object).getContext();
                if (context != null) {
                    return context;
                }
            } catch (Throwable ignored) {
            }
        }

        Class<?> current = object.getClass();

        while (current != null && current != Object.class) {
            Field[] fields;

            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                current = current.getSuperclass();
                continue;
            }

            for (Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(object);

                    if (value instanceof ContentProvider) {
                        Context context = ((ContentProvider) value).getContext();

                        if (context != null) {
                            return context;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            current = current.getSuperclass();
        }

        return null;
    }

    private static Application currentApplicationByReflection() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);

            Object value = currentApplication.invoke(null);

            if (value instanceof Application) {
                return (Application) value;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentActivityThread = activityThread.getDeclaredMethod("currentActivityThread");
            currentActivityThread.setAccessible(true);

            Object thread = currentActivityThread.invoke(null);

            if (thread == null) {
                return null;
            }

            Field initialApplication = activityThread.getDeclaredField("mInitialApplication");
            initialApplication.setAccessible(true);

            Object value = initialApplication.get(thread);

            if (value instanceof Application) {
                return (Application) value;
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static Class<?> findClass(String name, ClassLoader classLoader) {
        try {
            return XposedHelpers.findClass(name, classLoader);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void logReplaceOnce(String type, String[] packages, Uri uri, int count) {
        String packageText = packages == null ? "unknown" : Arrays.toString(packages);
        String key = type + "|" + packageText + "|" + uri;

        if (LOG_DEDUP.add(key)) {
            log("replace contacts query type="
                    + type
                    + ", caller="
                    + packageText
                    + ", uri="
                    + uri
                    + ", count="
                    + count);
        }
    }

    private static void logOnce(String key, String message) {
        if (LOG_DEDUP.add(key)) {
            log(message);
        }
    }

    private static void log(String message) {
        XposedBridge.log("FakeContactBook: " + message);
    }

    private interface FakeContactBookCursor {
    }

    private static final class QueryParts {
        String[] projection;
        String selection;
        String[] selectionArgs;

        static QueryParts from(Object[] args) {
            QueryParts parts = new QueryParts();

            if (args == null) {
                return parts;
            }

            for (Object arg : args) {
                if (arg instanceof String[] && parts.projection == null) {
                    parts.projection = (String[]) arg;
                    break;
                }
            }

            for (int i = 0; i < args.length; i++) {
                Object arg = args[i];

                if (arg instanceof Bundle) {
                    Bundle bundle = (Bundle) arg;

                    if (parts.projection == null) {
                        parts.projection = bundle.getStringArray("android:query-arg-projection");
                    }

                    parts.selection = bundle.getString("android:query-arg-sql-selection");
                    parts.selectionArgs = bundle.getStringArray("android:query-arg-sql-selection-args");
                } else if (arg instanceof String && i == 2 && parts.selection == null) {
                    parts.selection = (String) arg;
                } else if (arg instanceof String[] && i == 3 && parts.selectionArgs == null) {
                    parts.selectionArgs = (String[]) arg;
                }
            }

            return parts;
        }
    }

    private static final class HookConfig {
        boolean enabled;
        boolean explicitRule;
        String mode = "disabled";
        ArrayList<HookContact> contacts = new ArrayList<>();
        long fetchedAt;

        static HookConfig disabled() {
            HookConfig config = new HookConfig();
            config.enabled = false;
            config.explicitRule = false;
            config.mode = "disabled";
            config.fetchedAt = System.currentTimeMillis();
            return config;
        }
    }

    private static final class HookContact {
        String name;
        String phone;
        String email;

        HookContact(String name, String phone, String email) {
            this.name = TextUtils.isEmpty(name) ? phone : name;
            this.phone = phone == null ? "" : phone;
            this.email = email == null ? "" : email;
        }
    }

    private static final class HookConfigBridge {
        private static final long CACHE_TTL_MS = 3000L;
        private static final ConcurrentHashMap<String, HookConfig> CACHE = new ConcurrentHashMap<>();

        static HookConfig get(Context context, String packageName) {
            if (context == null || TextUtils.isEmpty(packageName)) {
                return HookConfig.disabled();
            }

            HookConfig cached = CACHE.get(packageName);
            long now = System.currentTimeMillis();

            if (cached != null && now - cached.fetchedAt < CACHE_TTL_MS) {
                return cached;
            }

            HookConfig loaded = loadFromProvider(context, packageName);
            CACHE.put(packageName, loaded);
            return loaded;
        }

        private static HookConfig loadFromProvider(Context context, String packageName) {
            Cursor cursor = null;

            try {
                Uri uri = Uri.parse(PROVIDER_URI)
                        .buildUpon()
                        .appendQueryParameter("pkg", packageName)
                        .build();

                cursor = context.getContentResolver().query(
                        uri,
                        new String[]{"enabled", "profile_json", "reason", "mode", "explicit"},
                        null,
                        null,
                        null
                );

                if (cursor == null || !cursor.moveToFirst()) {
                    return HookConfig.disabled();
                }

                int enabledIndex = cursor.getColumnIndex("enabled");
                int jsonIndex = cursor.getColumnIndex("profile_json");
                int modeIndex = cursor.getColumnIndex("mode");
                int explicitIndex = cursor.getColumnIndex("explicit");

                boolean enabled = enabledIndex >= 0 && cursor.getInt(enabledIndex) == 1;

                if (!enabled) {
                    return HookConfig.disabled();
                }

                String json = jsonIndex >= 0 ? cursor.getString(jsonIndex) : "";

                HookConfig config = parseProfileJson(json);
                config.enabled = true;
                config.mode = modeIndex >= 0 ? cursor.getString(modeIndex) : "";
                config.explicitRule = explicitIndex >= 0 && cursor.getInt(explicitIndex) == 1;
                config.fetchedAt = System.currentTimeMillis();

                return config;
            } catch (Throwable throwable) {
                return HookConfig.disabled();
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        private static HookConfig parseProfileJson(String json) throws Exception {
            HookConfig config = new HookConfig();

            JSONObject object = new JSONObject(json);
            JSONArray contacts = object.optJSONArray("contacts");

            if (contacts != null) {
                for (int i = 0; i < contacts.length(); i++) {
                    JSONObject item = contacts.optJSONObject(i);

                    if (item == null) {
                        continue;
                    }

                    String name = item.optString("name", "");
                    String phone = item.optString("phone", "");
                    String email = item.optString("email", "");

                    if (!TextUtils.isEmpty(phone)) {
                        config.contacts.add(new HookContact(name, phone, email));
                    }
                }
            }

            return config;
        }
    }

    private enum UriKind {
        CONTACTS,
        RAW_CONTACTS,
        DATA,
        PHONE,
        EMAIL,
        PHONE_LOOKUP,
        EMPTY
    }

    private enum RowKind {
        CONTACT,
        RAW_CONTACT,
        PHONE,
        EMAIL,
        NAME,
        PHONE_LOOKUP
    }

    private static final class FakeContactsCursorFactory {
        private static final String MIME_PHONE = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
        private static final String MIME_EMAIL = ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE;
        private static final String MIME_NAME = ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE;

        static Cursor build(
                Uri uri,
                QueryParts query,
                ArrayList<HookContact> contacts,
                Cursor original
        ) {
            UriKind kind = detectKind(uri, query == null ? null : query.selection, query == null ? null : query.selectionArgs);

            String[] columns = resolveColumns(
                    original,
                    query == null ? null : query.projection,
                    defaultColumns(kind)
            );

            String[] valueKeys = resolveValueKeys(
                    original,
                    query == null ? null : query.projection,
                    columns
            );

            ArrayList<FakeRow> rows = buildRows(
                    uri,
                    kind,
                    contacts,
                    query == null ? null : query.selection,
                    query == null ? null : query.selectionArgs
            );

            Bundle extras = buildSafeExtras(uri, original, rows.size());

            return new FakePreservingCursor(original, columns, valueKeys, rows, extras);
        }

        private static ArrayList<FakeRow> buildRows(
                Uri uri,
                UriKind kind,
                ArrayList<HookContact> contacts,
                String selection,
                String[] selectionArgs
        ) {
            ArrayList<FakeRow> rows = new ArrayList<>();

            if (kind == UriKind.EMPTY) {
                return rows;
            }

            ArrayList<HookContact> safeContacts = contacts == null ? new ArrayList<>() : contacts;
            Long requestedContactId = requestedContactId(uri, selection, selectionArgs);

            for (int i = 0; i < safeContacts.size(); i++) {
                long contactId = contactId(i);

                if (requestedContactId != null && requestedContactId != contactId) {
                    continue;
                }

                HookContact contact = safeContacts.get(i);

                switch (kind) {
                    case CONTACTS:
                        rows.add(new FakeRow(RowKind.CONTACT, contact, i));
                        break;

                    case RAW_CONTACTS:
                        rows.add(new FakeRow(RowKind.RAW_CONTACT, contact, i));
                        break;

                    case PHONE_LOOKUP:
                        String lookupNumber = uri == null ? "" : uri.getLastPathSegment();
                        if (matchesPhoneLookup(contact.phone, lookupNumber)) {
                            rows.add(new FakeRow(RowKind.PHONE_LOOKUP, contact, i));
                        }
                        break;

                    case EMAIL:
                        if (!TextUtils.isEmpty(contact.email)) {
                            rows.add(new FakeRow(RowKind.EMAIL, contact, i));
                        }
                        break;

                    case PHONE:
                        rows.add(new FakeRow(RowKind.PHONE, contact, i));
                        break;

                    case DATA:
                    default:
                        appendDataRows(rows, contact, i, selection, selectionArgs);
                        break;
                }
            }

            return rows;
        }

        private static void appendDataRows(
                ArrayList<FakeRow> rows,
                HookContact contact,
                int index,
                String selection,
                String[] selectionArgs
        ) {
            boolean mentionsMime = mentionsMime(selection, selectionArgs);
            boolean wantsPhone = wantsMime(selection, selectionArgs, MIME_PHONE);
            boolean wantsEmail = wantsMime(selection, selectionArgs, MIME_EMAIL);
            boolean wantsName = wantsMime(selection, selectionArgs, MIME_NAME);

            if (!mentionsMime || wantsName) {
                rows.add(new FakeRow(RowKind.NAME, contact, index));
            }

            if (!mentionsMime || wantsPhone) {
                rows.add(new FakeRow(RowKind.PHONE, contact, index));
            }

            if ((!mentionsMime || wantsEmail) && !TextUtils.isEmpty(contact.email)) {
                rows.add(new FakeRow(RowKind.EMAIL, contact, index));
            }
        }

        private static UriKind detectKind(Uri uri, String selection, String[] selectionArgs) {
            String path = uri == null || uri.getPath() == null
                    ? ""
                    : uri.getPath().toLowerCase(Locale.US);

            if (path.contains("deleted_contacts")
                    || path.equals("/profile")
                    || path.startsWith("/profile/")
                    || path.contains("/groups")
                    || path.contains("/directories")
                    || path.contains("/settings")
                    || path.contains("/aggregation_exceptions")) {
                return UriKind.EMPTY;
            }

            if (path.contains("phone_lookup")) {
                return UriKind.PHONE_LOOKUP;
            }

            if (path.contains("data/phones") || path.endsWith("/phones") || path.contains("/phones/")) {
                return UriKind.PHONE;
            }

            if (path.contains("data/emails") || path.endsWith("/emails") || path.contains("/emails/")) {
                return UriKind.EMAIL;
            }

            if (path.contains("raw_contacts")) {
                return UriKind.RAW_CONTACTS;
            }

            if (path.contains("/data") || path.equals("/data")) {
                return UriKind.DATA;
            }

            if (path.contains("contacts") || TextUtils.isEmpty(path)) {
                return UriKind.CONTACTS;
            }

            if (wantsMime(selection, selectionArgs, MIME_PHONE)) {
                return UriKind.PHONE;
            }

            if (wantsMime(selection, selectionArgs, MIME_EMAIL)) {
                return UriKind.EMAIL;
            }

            return UriKind.DATA;
        }

        private static String[] defaultColumns(UriKind kind) {
            switch (kind) {
                case EMPTY:
                    return new String[]{"_id"};

                case CONTACTS:
                    return new String[]{
                            "_id",
                            "display_name",
                            "display_name_primary",
                            "display_name_alt",
                            "lookup",
                            "lookup_key",
                            "has_phone_number",
                            "photo_id",
                            "photo_uri",
                            "photo_thumb_uri",
                            "starred",
                            "times_contacted",
                            "sort_key",
                            "sort_key_primary",
                            "sort_key_alt"
                    };

                case RAW_CONTACTS:
                    return new String[]{
                            "_id",
                            "contact_id",
                            "display_name_primary",
                            "account_name",
                            "account_type",
                            "deleted",
                            "raw_contact_is_read_only"
                    };

                case PHONE_LOOKUP:
                    return new String[]{
                            "_id",
                            "contact_id",
                            "data_id",
                            "number",
                            "normalized_number",
                            "display_name",
                            "display_name_primary",
                            "name",
                            "lookup",
                            "lookup_key",
                            "type",
                            "label",
                            "photo_id",
                            "photo_uri",
                            "photo_thumb_uri"
                    };

                case EMAIL:
                    return new String[]{
                            "_id",
                            "contact_id",
                            "raw_contact_id",
                            "display_name",
                            "display_name_primary",
                            "data1",
                            "data2",
                            "data3",
                            "mimetype",
                            "lookup",
                            "lookup_key"
                    };

                case PHONE:
                case DATA:
                default:
                    return new String[]{
                            "_id",
                            "contact_id",
                            "raw_contact_id",
                            "display_name",
                            "display_name_primary",
                            "display_name_alt",
                            "name",
                            "data1",
                            "data4",
                            "data2",
                            "data3",
                            "mimetype",
                            "lookup",
                            "lookup_key",
                            "has_phone_number",
                            "photo_id",
                            "photo_uri",
                            "photo_thumb_uri",
                            "sort_key",
                            "sort_key_primary",
                            "sort_key_alt"
                    };
            }
        }

        private static String[] resolveColumns(Cursor original, String[] projection, String[] defaults) {
            try {
                if (original != null) {
                    String[] names = original.getColumnNames();

                    if (names != null && names.length > 0) {
                        return names;
                    }
                }
            } catch (Throwable ignored) {
            }

            if (projection != null && projection.length > 0) {
                String[] display = new String[projection.length];

                for (int i = 0; i < projection.length; i++) {
                    String name = displayColumnName(projection[i]);
                    display[i] = TextUtils.isEmpty(name) ? projection[i] : name;
                }

                return display;
            }

            return defaults == null || defaults.length == 0 ? new String[]{"_id"} : defaults;
        }

        private static String[] resolveValueKeys(Cursor original, String[] projection, String[] columns) {
            if (projection != null && projection.length == columns.length) {
                return projection;
            }

            return columns;
        }

        private static Bundle buildSafeExtras(Uri uri, Cursor original, int count) {
            Bundle out = new Bundle();

            boolean wantsAddressBookIndex = false;

            try {
                wantsAddressBookIndex = uri != null
                        && uri.getBooleanQueryParameter(EXTRA_ADDRESS_BOOK_INDEX, false);
            } catch (Throwable ignored) {
            }

            try {
                if (!wantsAddressBookIndex && original != null) {
                    Bundle originalExtras = original.getExtras();

                    if (originalExtras != null) {
                        wantsAddressBookIndex =
                                originalExtras.containsKey(EXTRA_ADDRESS_BOOK_INDEX_TITLES)
                                        || originalExtras.containsKey(EXTRA_ADDRESS_BOOK_INDEX_COUNTS);
                    }
                }
            } catch (Throwable ignored) {
            }

            if (wantsAddressBookIndex) {
                out.putStringArray(EXTRA_ADDRESS_BOOK_INDEX_TITLES, new String[]{"#"});
                out.putIntArray(EXTRA_ADDRESS_BOOK_INDEX_COUNTS, new int[]{count});
            }

            out.putInt("android.content.extra.TOTAL_COUNT", count);
            out.putInt("android.content.extra.EXTRA_TOTAL_COUNT", count);

            return out;
        }

        private static Object valueFor(String rawColumn, RowKind rowKind, HookContact contact, int index) {
            String column = normalizeColumn(rawColumn);

            long contactId = contactId(index);
            long rawId = rawContactId(index);
            long rowId = rowId(rowKind, index);
            String lookup = "fake_lookup_" + contactId;
            String normalizedPhone = normalizePhone(contact.phone);

            if ("_id".equals(column) || "id".equals(column)) {
                return rowId;
            }

            if ("contact_id".equals(column)) {
                return contactId;
            }

            if ("raw_contact_id".equals(column) || "name_raw_contact_id".equals(column)) {
                return rawId;
            }

            if ("data_id".equals(column)) {
                return rowId;
            }

            if ("lookup".equals(column) || "lookup_key".equals(column)) {
                return lookup;
            }

            if ("display_name".equals(column)
                    || "display_name_primary".equals(column)
                    || "display_name_alt".equals(column)
                    || "name".equals(column)
                    || "sort_key".equals(column)
                    || "sort_key_primary".equals(column)
                    || "sort_key_alt".equals(column)) {
                return contact.name;
            }

            if ("has_phone_number".equals(column)) {
                return TextUtils.isEmpty(contact.phone) ? 0 : 1;
            }

            if ("photo_id".equals(column) || "photo_file_id".equals(column)) {
                return 0;
            }

            if ("photo_uri".equals(column) || "photo_thumb_uri".equals(column)) {
                return "";
            }

            if ("starred".equals(column)
                    || "pinned".equals(column)
                    || "send_to_voicemail".equals(column)
                    || "times_contacted".equals(column)
                    || "last_time_contacted".equals(column)
                    || "contact_presence".equals(column)
                    || "contact_status".equals(column)) {
                return 0;
            }

            if ("in_visible_group".equals(column)
                    || "is_primary".equals(column)
                    || "is_super_primary".equals(column)) {
                return 1;
            }

            if ("account_name".equals(column)
                    || "account_type".equals(column)
                    || "data_set".equals(column)) {
                return "";
            }

            if ("deleted".equals(column) || "raw_contact_is_read_only".equals(column)) {
                return 0;
            }

            if ("mimetype".equals(column)) {
                if (rowKind == RowKind.PHONE || rowKind == RowKind.PHONE_LOOKUP) {
                    return MIME_PHONE;
                }

                if (rowKind == RowKind.EMAIL) {
                    return MIME_EMAIL;
                }

                if (rowKind == RowKind.NAME) {
                    return MIME_NAME;
                }

                return "";
            }

            if ("number".equals(column)
                    || "phone_number".equals(column)
                    || "formatted_number".equals(column)
                    || "normalized_number".equals(column)
                    || "normalized_phone_number".equals(column)) {
                if ("normalized_number".equals(column) || "normalized_phone_number".equals(column)) {
                    return normalizedPhone;
                }

                return contact.phone;
            }

            if ("type".equals(column) || "data2".equals(column)) {
                if (rowKind == RowKind.PHONE || rowKind == RowKind.PHONE_LOOKUP) {
                    return ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE;
                }

                if (rowKind == RowKind.EMAIL) {
                    return ContactsContract.CommonDataKinds.Email.TYPE_OTHER;
                }

                return 0;
            }

            if ("label".equals(column) || "data3".equals(column)) {
                return "";
            }

            if ("data1".equals(column)) {
                if (rowKind == RowKind.PHONE || rowKind == RowKind.PHONE_LOOKUP) {
                    return contact.phone;
                }

                if (rowKind == RowKind.EMAIL) {
                    return contact.email;
                }

                if (rowKind == RowKind.NAME) {
                    return contact.name;
                }

                return contact.name;
            }

            if ("data4".equals(column)) {
                return rowKind == RowKind.PHONE || rowKind == RowKind.PHONE_LOOKUP
                        ? normalizedPhone
                        : "";
            }

            if (column.startsWith("data")) {
                return "";
            }

            if ("given_name".equals(column)) {
                return givenName(contact.name);
            }

            if ("family_name".equals(column)) {
                return familyName(contact.name);
            }

            if (column.contains("id")) {
                return rowId;
            }

            if (column.contains("count")
                    || column.contains("type")
                    || column.contains("status")
                    || column.contains("presence")
                    || column.contains("starred")
                    || column.contains("pinned")) {
                return 0;
            }

            if (column.contains("number") || column.contains("phone")) {
                return contact.phone;
            }

            if (column.contains("name")
                    || column.contains("sort")
                    || column.contains("key")
                    || column.contains("label")
                    || column.contains("section")) {
                return contact.name;
            }

            return "";
        }

        private static boolean mentionsMime(String selection, String[] selectionArgs) {
            if (selection != null && selection.toLowerCase(Locale.US).contains("mimetype")) {
                return true;
            }

            if (selectionArgs != null) {
                for (String arg : selectionArgs) {
                    if (arg != null && arg.startsWith("vnd.android.cursor.item/")) {
                        return true;
                    }
                }
            }

            return false;
        }

        private static boolean wantsMime(String selection, String[] selectionArgs, String mime) {
            if (TextUtils.isEmpty(mime)) {
                return false;
            }

            if (selection != null && selection.contains(mime)) {
                return true;
            }

            if (selectionArgs != null) {
                for (String arg : selectionArgs) {
                    if (mime.equals(arg)) {
                        return true;
                    }
                }
            }

            return false;
        }

        private static Long requestedContactId(Uri uri, String selection, String[] selectionArgs) {
            if (uri != null) {
                List<String> segments = uri.getPathSegments();

                for (int i = 0; i < segments.size() - 1; i++) {
                    if ("contacts".equalsIgnoreCase(segments.get(i))) {
                        Long id = parseLongOrNull(segments.get(i + 1));

                        if (id != null) {
                            return id;
                        }
                    }
                }
            }

            if (selection != null
                    && selection.toLowerCase(Locale.US).contains("contact_id")
                    && selectionArgs != null) {
                for (String arg : selectionArgs) {
                    Long id = parseLongOrNull(arg);

                    if (id != null) {
                        return id;
                    }
                }
            }

            return null;
        }

        private static boolean matchesPhoneLookup(String phone, String lookupNumber) {
            if (TextUtils.isEmpty(lookupNumber)) {
                return false;
            }

            String target = normalizePhone(Uri.decode(lookupNumber));
            String source = normalizePhone(phone);

            if (TextUtils.isEmpty(target) || TextUtils.isEmpty(source)) {
                return false;
            }

            return source.endsWith(target)
                    || target.endsWith(source)
                    || source.contains(target)
                    || target.contains(source);
        }

        private static Long parseLongOrNull(String value) {
            try {
                if (TextUtils.isEmpty(value)) {
                    return null;
                }

                return Long.parseLong(value);
            } catch (Throwable ignored) {
                return null;
            }
        }

        private static String normalizeColumn(String column) {
            if (column == null) {
                return "";
            }

            String lower = column.trim().toLowerCase(Locale.US);

            int asIndex = lower.lastIndexOf(" as ");

            if (asIndex >= 0) {
                lower = lower.substring(0, asIndex).trim();
            }

            int dot = lower.lastIndexOf('.');

            if (dot >= 0 && dot < lower.length() - 1) {
                lower = lower.substring(dot + 1);
            }

            lower = lower.replace("`", "").replace("\"", "").trim();

            return lower;
        }

        private static String displayColumnName(String projectionItem) {
            if (projectionItem == null) {
                return "";
            }

            String lower = projectionItem.toLowerCase(Locale.US);
            int asIndex = lower.lastIndexOf(" as ");

            if (asIndex >= 0 && asIndex + 4 < projectionItem.length()) {
                return projectionItem.substring(asIndex + 4)
                        .replace("`", "")
                        .replace("\"", "")
                        .trim();
            }

            return projectionItem;
        }

        private static String normalizePhone(String phone) {
            if (phone == null) {
                return "";
            }

            StringBuilder builder = new StringBuilder();

            for (int i = 0; i < phone.length(); i++) {
                char c = phone.charAt(i);

                if (Character.isDigit(c)) {
                    builder.append(c);
                } else if (c == '+' && builder.length() == 0) {
                    builder.append(c);
                }
            }

            return builder.toString();
        }

        private static String givenName(String name) {
            if (TextUtils.isEmpty(name)) {
                return "";
            }

            String[] parts = name.trim().split("\\s+", 2);
            return parts[0];
        }

        private static String familyName(String name) {
            if (TextUtils.isEmpty(name)) {
                return "";
            }

            String[] parts = name.trim().split("\\s+", 2);
            return parts.length > 1 ? parts[1] : "";
        }

        private static long contactId(int index) {
            return 100000L + index + 1;
        }

        private static long rawContactId(int index) {
            return 200000L + index + 1;
        }

        private static long rowId(RowKind kind, int index) {
            long base;

            switch (kind) {
                case CONTACT:
                    base = 100000L;
                    break;

                case RAW_CONTACT:
                    base = 200000L;
                    break;

                case PHONE:
                case PHONE_LOOKUP:
                    base = 300000L;
                    break;

                case NAME:
                    base = 400000L;
                    break;

                case EMAIL:
                    base = 500000L;
                    break;

                default:
                    base = 900000L;
                    break;
            }

            return base + index + 1;
        }

        private static final class FakeRow {
            final RowKind rowKind;
            final HookContact contact;
            final int index;

            FakeRow(RowKind rowKind, HookContact contact, int index) {
                this.rowKind = rowKind;
                this.contact = contact;
                this.index = index;
            }
        }
    }

    private static final class EmptyPreservingCursor extends AbstractCursor implements FakeContactBookCursor {
        private final Cursor original;
        private final String[] columns;
        private final Bundle extras;

        EmptyPreservingCursor(Cursor original, Uri uri) {
            this.original = original;
            this.columns = resolveColumns(original);
            this.extras = buildSafeExtras(original, uri);
        }

        @Override
        public int getCount() {
            return 0;
        }

        @Override
        public String[] getColumnNames() {
            return columns;
        }

        @Override
        public String getString(int column) {
            return "";
        }

        @Override
        public short getShort(int column) {
            return 0;
        }

        @Override
        public int getInt(int column) {
            return 0;
        }

        @Override
        public long getLong(int column) {
            return 0L;
        }

        @Override
        public float getFloat(int column) {
            return 0f;
        }

        @Override
        public double getDouble(int column) {
            return 0d;
        }

        @Override
        public boolean isNull(int column) {
            return true;
        }

        @Override
        public byte[] getBlob(int column) {
            return null;
        }

        @Override
        public int getType(int column) {
            return FIELD_TYPE_NULL;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public Bundle respond(Bundle extras) {
            return Bundle.EMPTY;
        }

        @Override
        public void close() {
            super.close();

            try {
                if (original != null) {
                    original.close();
                }
            } catch (Throwable ignored) {
            }
        }

        private static String[] resolveColumns(Cursor original) {
            try {
                if (original != null) {
                    String[] names = original.getColumnNames();

                    if (names != null && names.length > 0) {
                        return names;
                    }
                }
            } catch (Throwable ignored) {
            }

            return new String[]{"_id"};
        }

        private static Bundle buildSafeExtras(Cursor original, Uri uri) {
            Bundle out = new Bundle();

            boolean wantsAddressBookIndex = false;

            try {
                wantsAddressBookIndex = uri != null
                        && uri.getBooleanQueryParameter(EXTRA_ADDRESS_BOOK_INDEX, false);
            } catch (Throwable ignored) {
            }

            try {
                if (!wantsAddressBookIndex && original != null) {
                    Bundle originalExtras = original.getExtras();

                    if (originalExtras != null) {
                        wantsAddressBookIndex =
                                originalExtras.containsKey(EXTRA_ADDRESS_BOOK_INDEX_TITLES)
                                        || originalExtras.containsKey(EXTRA_ADDRESS_BOOK_INDEX_COUNTS);
                    }
                }
            } catch (Throwable ignored) {
            }

            if (wantsAddressBookIndex) {
                out.putStringArray(EXTRA_ADDRESS_BOOK_INDEX_TITLES, new String[]{"#"});
                out.putIntArray(EXTRA_ADDRESS_BOOK_INDEX_COUNTS, new int[]{0});
            }

            out.putInt("android.content.extra.TOTAL_COUNT", 0);
            out.putInt("android.content.extra.EXTRA_TOTAL_COUNT", 0);

            return out;
        }
    }

    private static final class FakePreservingCursor extends AbstractCursor implements FakeContactBookCursor {
        private final Cursor original;
        private final String[] columns;
        private final String[] valueKeys;
        private final ArrayList<FakeContactsCursorFactory.FakeRow> rows;
        private final Bundle extras;

        FakePreservingCursor(
                Cursor original,
                String[] columns,
                String[] valueKeys,
                ArrayList<FakeContactsCursorFactory.FakeRow> rows,
                Bundle extras
        ) {
            this.original = original;
            this.columns = columns == null || columns.length == 0 ? new String[]{"_id"} : columns;
            this.valueKeys = valueKeys == null || valueKeys.length == 0 ? this.columns : valueKeys;
            this.rows = rows == null ? new ArrayList<>() : rows;
            this.extras = extras == null ? Bundle.EMPTY : extras;
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public String[] getColumnNames() {
            return columns;
        }

        private Object valueAt(int columnIndex) {
            int position = getPosition();

            if (position < 0 || position >= rows.size()) {
                return null;
            }

            if (columnIndex < 0 || columnIndex >= valueKeys.length) {
                return null;
            }

            FakeContactsCursorFactory.FakeRow row = rows.get(position);
            return FakeContactsCursorFactory.valueFor(
                    valueKeys[columnIndex],
                    row.rowKind,
                    row.contact,
                    row.index
            );
        }

        @Override
        public String getString(int column) {
            Object value = valueAt(column);
            return value == null ? null : String.valueOf(value);
        }

        @Override
        public short getShort(int column) {
            return (short) getLong(column);
        }

        @Override
        public int getInt(int column) {
            return (int) getLong(column);
        }

        @Override
        public long getLong(int column) {
            Object value = valueAt(column);

            if (value instanceof Number) {
                return ((Number) value).longValue();
            }

            if (value == null) {
                return 0L;
            }

            try {
                return Long.parseLong(String.valueOf(value));
            } catch (Throwable ignored) {
                return 0L;
            }
        }

        @Override
        public float getFloat(int column) {
            return (float) getDouble(column);
        }

        @Override
        public double getDouble(int column) {
            Object value = valueAt(column);

            if (value instanceof Number) {
                return ((Number) value).doubleValue();
            }

            if (value == null) {
                return 0.0d;
            }

            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (Throwable ignored) {
                return 0.0d;
            }
        }

        @Override
        public boolean isNull(int column) {
            return valueAt(column) == null;
        }

        @Override
        public byte[] getBlob(int column) {
            Object value = valueAt(column);
            return value instanceof byte[] ? (byte[]) value : null;
        }

        @Override
        public int getType(int column) {
            Object value = valueAt(column);

            if (value == null) {
                return FIELD_TYPE_NULL;
            }

            if (value instanceof byte[]) {
                return FIELD_TYPE_BLOB;
            }

            if (value instanceof Float || value instanceof Double) {
                return FIELD_TYPE_FLOAT;
            }

            if (value instanceof Number || value instanceof Boolean) {
                return FIELD_TYPE_INTEGER;
            }

            return FIELD_TYPE_STRING;
        }

        @Override
        public Bundle getExtras() {
            return extras;
        }

        @Override
        public Bundle respond(Bundle extras) {
            return Bundle.EMPTY;
        }

        @Override
        public void close() {
            super.close();

            try {
                if (original != null) {
                    original.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static final String TEST_CALLER_PACKAGE_PARAM = "_fcb_test_pkg";

    private static String resolveTrustedTestPackage(String[] realCallerPackages, Uri uri) {
        if (uri == null || realCallerPackages == null) {
            return "";
        }

        /*
         * 只有宿主 App 自己发起的查询，才允许使用测试包名。
         */
        if (!containsPackage(realCallerPackages, MODULE_PACKAGE)) {
            return "";
        }

        String testPackage = uri.getQueryParameter(TEST_CALLER_PACKAGE_PARAM);

        if (!looksLikePackageName(testPackage)) {
            return "";
        }

        return testPackage;
    }

    private static final String PROVIDER_HOOK_PROBE_PARAM = "_fcb_provider_probe";
    private static final String PROVIDER_HOOK_PROBE_COLUMN = "fcb_provider_hooked";
    private static final String PROVIDER_HOOK_PROBE_VERSION_COLUMN = "fcb_provider_hook_version";
    private static final int PROVIDER_HOOK_PROBE_VERSION = 1;
    private static boolean isProviderHookProbeUri(Uri uri) {
        if (!isContactsUri(uri)) {
            return false;
        }

        return "1".equals(uri.getQueryParameter(PROVIDER_HOOK_PROBE_PARAM));
    }

    private static Cursor buildProviderHookProbeCursor() {
        android.database.MatrixCursor cursor = new android.database.MatrixCursor(new String[]{
                PROVIDER_HOOK_PROBE_COLUMN,
                PROVIDER_HOOK_PROBE_VERSION_COLUMN,
                "message"
        });

        cursor.addRow(new Object[]{
                1,
                PROVIDER_HOOK_PROBE_VERSION,
                "com.android.providers.contacts hooked"
        });

        return cursor;
    }

    private static boolean containsPackage(String[] packages, String wanted) {
        if (packages == null || TextUtils.isEmpty(wanted)) {
            return false;
        }

        for (String pkg : packages) {
            if (wanted.equals(pkg)) {
                return true;
            }
        }

        return false;
    }
}