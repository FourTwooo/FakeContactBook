package com.fourtwo.fakecontactbook.xposedmodels;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.text.TextUtils;
import android.util.Base64;
import android.util.LruCache;

import androidx.annotation.RequiresApi;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

@RequiresApi(api = Build.VERSION_CODES.N)
public class mobileqq {
    // 只接受通讯录里出现过的 unifiedCode。
// 如果 KNOWN_UNIFIED_CODES 还没收集到，则先不拦截。
    private static final boolean ONLY_KNOWN_UNIFIED = true;

    private static final ExecutorService SUBMIT_EXECUTOR = Executors.newSingleThreadExecutor();
    // 记录已经提交过联系人的手机号（仅限联系信息，即 nick/account 等）
    private static final Set<String> SUBMITTED_CONTACTS = ConcurrentHashMap.newKeySet();
    // 记录已经提交过头像的手机号（可按需改为记录 base64 hash 以实现更新）
    private static final Set<String> SUBMITTED_AVATARS = ConcurrentHashMap.newKeySet();
    private static void submitAsync(final Context appContext,
                                    final String platform,
                                    final String phone,
                                    final Map<String, Object> fields,
                                    final Set<String> submittedSet) {
        if (phone == null || !submittedSet.add(phone)) {
            // 已提交过，直接返回
            return;
        }
        XposedBridge.log(phone + ": "+ fields);
        SUBMIT_EXECUTOR.execute(() -> {
            SocialProfileSubmitter.submit(appContext, platform, phone, fields);
        });
    }
    // 是否只接受 faceType == 11。
// ContactBindedActivity 当前代码用的是 11。
// 如果你怕漏主列表 adapter.h 的头像，可以保持 false。
    private static final boolean ONLY_FACE_TYPE_11 = false;

    private static final Set<String> KNOWN_UNIFIED_CODES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static final Set<String> HOOKED_CLASSES =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final ConcurrentHashMap<String, String> UNIFIED_TO_PHONE = new ConcurrentHashMap<>();
    private static final Set<String> HOOKED_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    // unifiedCode -> avatar base64
// 不带 data:image/jpeg;base64, 前缀，只存纯 base64
    private static final LruCache<String, String> AVATAR_BASE64_CACHE =
            new LruCache<String, String>(8 * 1024) {
                @Override
                protected int sizeOf(String key, String value) {
                    if (value == null) return 1;
                    return Math.max(1, value.length() / 1024);
                }
            };
    private static String bitmapToBase64(Bitmap bitmap) {
        return bitmapToBase64(bitmap, Bitmap.CompressFormat.JPEG, 85);
    }

    private static String bitmapToBase64(
            Bitmap bitmap,
            Bitmap.CompressFormat format,
            int quality
    ) {
        if (bitmap == null) return null;
        if (bitmap.isRecycled()) return null;

        ByteArrayOutputStream baos = null;

        try {
            baos = new ByteArrayOutputStream();

            boolean ok = bitmap.compress(format, quality, baos);
            if (!ok) {
//                XposedBridge.log("[QQ头像Base64] bitmap.compress failed");
                return null;
            }

            byte[] bytes = baos.toByteArray();
            if (bytes == null || bytes.length == 0) {
                return null;
            }

            // NO_WRAP 很重要：避免 base64 中间插入换行，方便放 JSON / 表单
            return Base64.encodeToString(bytes, Base64.NO_WRAP);

        } catch (Throwable t) {
            XposedBridge.log("[QQ头像Base64] bitmapToBase64 error: " + t);
            return null;

        } finally {
            if (baos != null) {
                try {
                    baos.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
    // 本地内存缓存：unifiedCode -> Bitmap
// 不上传，不写文件。
    private static final LruCache<String, Bitmap> AVATAR_CACHE =
            new LruCache<String, Bitmap>(8 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    if (value == null) return 1;
                    try {
                        return Math.max(1, value.getByteCount() / 1024);
                    } catch (Throwable t) {
                        return 1;
                    }
                }
            };

    private static class FieldHit {
        final Field field;
        final Object value;

        FieldHit(Field field, Object value) {
            this.field = field;
            this.value = value;
        }
    }

    private interface ObjectFeature {
        boolean matches(Object value);
    }

    private static void hookQQAvatarAuto(final Context appContext, final ClassLoader cl) {
        try {
            Class<?> activityClazz = XposedHelpers.findClass(
                    "com.tencent.mobileqq.activity.ContactBindedActivity",
                    cl
            );

            XposedHelpers.findAndHookMethod(
                    activityClazz,
                    "doOnCreate",
                    Bundle.class,
                    new XC_MethodHook() {

                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object activity = param.thisObject;
                            if (activity == null) return;

                            // 1. 自动匹配原来的 f169512v0：IFaceDecoder
                            ArrayList<FieldHit> decoderHits = findFaceDecoderFields(activity, cl);
                            if (decoderHits.isEmpty()) {
                                XposedBridge.log("[QQ头像] 未找到 IFaceDecoder 特征字段");
                            } else {
                                for (FieldHit hit : decoderHits) {
                                    Object decoder = hit.value;
                                    if (decoder == null) continue;

//                                    XposedBridge.log(
//                                            "[QQ头像] 命中 FaceDecoder 字段: "
//                                                    + hit.field.getDeclaringClass().getName()
//                                                    + "#"
//                                                    + hit.field.getName()
//                                                    + " fieldType="
//                                                    + hit.field.getType().getName()
//                                                    + " realType="
//                                                    + decoder.getClass().getName()
//                                    );

                                    hookFaceDecoderClass(appContext, cl, decoder.getClass());
                                }
                            }

                            // 2. 自动匹配原来的 E0：DecodeTaskCompletionListener
                            ArrayList<FieldHit> listenerHits = findDecodeListenerFields(activity, cl);
                            if (listenerHits.isEmpty()) {
                                XposedBridge.log("[QQ头像] 未找到 DecodeTaskCompletionListener 特征字段");
                            } else {
                                for (FieldHit hit : listenerHits) {
                                    Object listener = hit.value;
                                    if (listener == null) continue;

                                    XposedBridge.log(
                                            "[QQ头像] 命中 DecodeListener 字段: "
                                                    + hit.field.getDeclaringClass().getName()
                                                    + "#"
                                                    + hit.field.getName()
                                                    + " fieldType="
                                                    + hit.field.getType().getName()
                                                    + " realType="
                                                    + listener.getClass().getName()
                                    );

                                    hookDecodeListenerClass(appContext, listener.getClass());
                                }
                            }
                        }
                    }
            );

            XposedBridge.log("[QQ头像] hook ContactBindedActivity.doOnCreate success");

        } catch (Throwable t) {
            XposedBridge.log("[QQ头像] hookQQAvatarAuto error: " + t);
        }
    }

    private static ArrayList<FieldHit> findFaceDecoderFields(final Object activity, final ClassLoader cl) {
        return findObjectFieldsByFeature(activity, new ObjectFeature() {
            @Override
            public boolean matches(Object value) {
                return looksLikeFaceDecoder(value, cl);
            }
        });
    }

    private static boolean looksLikeFaceDecoder(Object value, ClassLoader cl) {
        if (value == null) return false;

        // 第一优先级：直接判断是否实现 IFaceDecoder
        Class<?> iface = findClassOrNull("com.tencent.mobileqq.app.face.IFaceDecoder", cl);
        if (iface != null && iface.isInstance(value)) {
            return true;
        }

        // 第二优先级：按方法特征判断
        Class<?> c = value.getClass();

        boolean hasCache =
                hasMethodSignature(c, "getBitmapFromCache", Bitmap.class, int.class, String.class)
                        || hasMethodSignature(c, "getBitmapFromCache", Bitmap.class, int.class, String.class, int.class)
                        || hasMethodSignature(c, "getBitmapFromCache", Bitmap.class, int.class, String.class, int.class, byte.class)
                        || hasMethodSignature(c, "getBitmapFromCacheFrom", Bitmap.class, int.class, String.class, int.class);

        boolean hasRequest =
                hasMethodSignature(c, "requestDecodeFace", boolean.class, String.class, int.class, boolean.class)
                        || hasMethodSignature(c, "requestDecodeFace", boolean.class, String.class, int.class, boolean.class, byte.class)
                        || hasMethodNameAndParamCount(c, "requestDecodeFace", 7);

        boolean hasListenerSetter =
                hasMethodNameAndParamCount(c, "setDecodeTaskCompletionListener", 1);

        return hasCache && hasRequest && hasListenerSetter;
    }

    private static ArrayList<FieldHit> findDecodeListenerFields(final Object activity, final ClassLoader cl) {
        return findObjectFieldsByFeature(activity, new ObjectFeature() {
            @Override
            public boolean matches(Object value) {
                return looksLikeDecodeListener(value, cl);
            }
        });
    }

    private static boolean looksLikeDecodeListener(Object value, ClassLoader cl) {
        if (value == null) return false;

        Class<?> iface = findClassOrNull(
                "com.tencent.mobileqq.avatar.listener.DecodeTaskCompletionListener",
                cl
        );

        if (iface != null && iface.isInstance(value)) {
            return true;
        }

        return hasMethodSignature(
                value.getClass(),
                "onDecodeTaskCompleted",
                void.class,
                int.class,
                int.class,
                String.class,
                Bitmap.class
        );
    }

    private static void hookFaceDecoderClass(final Context appContext, final ClassLoader cl, Class<?> decoderClass) {
        if (decoderClass == null) return;

        String classKey = "decoder:" + decoderClass.getName();
        if (!HOOKED_CLASSES.add(classKey)) {
            return;
        }

        XposedBridge.log("[QQ头像] 开始 hook FaceDecoder realClass=" + decoderClass.getName());

        XC_MethodHook cacheHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Object result = param.getResult();
                if (!(result instanceof Bitmap)) return;

                String unifiedCode = getStringArg(param.args, 1);
                if (TextUtils.isEmpty(unifiedCode)) return;

                int faceType = getIntArg(param.args, 0, -1);
                if (!acceptFaceType(faceType)) return;

                acceptAvatarBitmap(
                        appContext,
                        unifiedCode,
                        (Bitmap) result,
                        "FaceDecoder.cache",
                        faceType
                );
            }
        };

        // 缓存命中路径
        hookMethodsByName(decoderClass, "getBitmapFromCache", cacheHook);
        hookMethodsByName(decoderClass, "getBitmapFromCacheFrom", cacheHook);

        // 缓存未命中请求路径：这里只能看到 unifiedCode，看不到 Bitmap
        hookMethodsByName(decoderClass, "requestDecodeFace", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                String unifiedCode = getStringArg(param.args, 0);
                if (TextUtils.isEmpty(unifiedCode)) return;

                int faceType = getIntArg(param.args, 1, -1);
                if (!acceptFaceType(faceType)) return;
                if (!acceptUnifiedCode(unifiedCode)) return;

//                XposedBridge.log(
//                        "[QQ头像请求] unifiedCode="
//                                + unifiedCode
//                                + " faceType="
//                                + faceType
//                                + " method=requestDecodeFace"
//                );
            }
        });

        // 后续如果有新的 listener 被设置，也自动 hook listener
        hookMethodsByName(decoderClass, "setDecodeTaskCompletionListener", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args == null || param.args.length == 0) return;

                Object listener = param.args[0];
                if (listener == null) return;

                if (looksLikeDecodeListener(listener, cl)) {
                    XposedBridge.log(
                            "[QQ头像] setDecodeTaskCompletionListener 命中 listener="
                                    + listener.getClass().getName()
                    );
                    hookDecodeListenerClass(appContext, listener.getClass());
                }
            }
        });
    }

    private static void hookDecodeListenerClass(final Context appContext, Class<?> listenerClass) {
        if (listenerClass == null) return;

        String classKey = "listener:" + listenerClass.getName();
        if (!HOOKED_CLASSES.add(classKey)) {
            return;
        }

        XposedBridge.log("[QQ头像] 开始 hook DecodeListener realClass=" + listenerClass.getName());

        hookMethodsByName(listenerClass, "onDecodeTaskCompleted", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args == null || param.args.length < 4) return;

                String unifiedCode = getStringArg(param.args, 2);
                Object bitmapObj = param.args[3];

                if (TextUtils.isEmpty(unifiedCode)) return;
                if (!(bitmapObj instanceof Bitmap)) return;

                int arg0 = getIntArg(param.args, 0, -1);
                int arg1 = getIntArg(param.args, 1, -1);

                acceptAvatarBitmap(
                        appContext,
                        unifiedCode,
                        (Bitmap) bitmapObj,
                        "DecodeListener.onDecodeTaskCompleted arg0=" + arg0 + " arg1=" + arg1,
                        arg0
                );
            }
        });
    }

    private static ArrayList<FieldHit> findObjectFieldsByFeature(Object owner, ObjectFeature feature) {
        ArrayList<FieldHit> result = new ArrayList<>();

        if (owner == null || feature == null) return result;

        Class<?> current = owner.getClass();

        while (current != null && current != Object.class) {
            Field[] fields;

            try {
                fields = current.getDeclaredFields();
            } catch (Throwable t) {
                current = current.getSuperclass();
                continue;
            }

            for (Field f : fields) {
                try {
                    int modifiers = f.getModifiers();

                    if (Modifier.isStatic(modifiers)) continue;
                    if (f.getType().isPrimitive()) continue;

                    f.setAccessible(true);
                    Object value = f.get(owner);
                    if (value == null) continue;

                    if (feature.matches(value)) {
                        result.add(new FieldHit(f, value));
                    }

                } catch (Throwable ignored) {
                }
            }

            current = current.getSuperclass();
        }

        return result;
    }

    private static Class<?> findClassOrNull(String name, ClassLoader cl) {
        try {
            return XposedHelpers.findClass(name, cl);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasMethodSignature(
            Class<?> clazz,
            String methodName,
            Class<?> returnType,
            Class<?>... paramTypes
    ) {
        if (clazz == null || TextUtils.isEmpty(methodName)) return false;

        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                current = current.getSuperclass();
                continue;
            }

            for (Method m : methods) {
                if (methodMatches(m, methodName, returnType, paramTypes)) {
                    return true;
                }
            }

            current = current.getSuperclass();
        }

        // getMethods() 可以覆盖 public 接口方法
        try {
            for (Method m : clazz.getMethods()) {
                if (methodMatches(m, methodName, returnType, paramTypes)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static boolean methodMatches(
            Method m,
            String methodName,
            Class<?> returnType,
            Class<?>[] paramTypes
    ) {
        if (m == null) return false;
        if (!methodName.equals(m.getName())) return false;

        if (returnType != null) {
            Class<?> actualReturn = m.getReturnType();

            if (returnType.isPrimitive() || actualReturn.isPrimitive()) {
                if (returnType != actualReturn) return false;
            } else if (!returnType.isAssignableFrom(actualReturn)) {
                return false;
            }
        }

        Class<?>[] actualParams = m.getParameterTypes();
        if (actualParams.length != paramTypes.length) return false;

        for (int i = 0; i < paramTypes.length; i++) {
            Class<?> expected = paramTypes[i];
            Class<?> actual = actualParams[i];

            if (expected == null) continue;

            if (expected.isPrimitive() || actual.isPrimitive()) {
                if (expected != actual) return false;
            } else if (!expected.isAssignableFrom(actual)) {
                return false;
            }
        }

        return true;
    }

    private static boolean hasMethodNameAndParamCount(Class<?> clazz, String methodName, int paramCount) {
        if (clazz == null || TextUtils.isEmpty(methodName)) return false;

        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                current = current.getSuperclass();
                continue;
            }

            for (Method m : methods) {
                if (methodName.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                    return true;
                }
            }

            current = current.getSuperclass();
        }

        try {
            for (Method m : clazz.getMethods()) {
                if (methodName.equals(m.getName()) && m.getParameterTypes().length == paramCount) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }

        return false;
    }

    private static void hookMethodsByName(Class<?> clazz, String methodName, XC_MethodHook callback) {
        if (clazz == null || TextUtils.isEmpty(methodName) || callback == null) return;

        Class<?> current = clazz;

        while (current != null && current != Object.class) {
            Method[] methods;

            try {
                methods = current.getDeclaredMethods();
            } catch (Throwable t) {
                current = current.getSuperclass();
                continue;
            }

            for (Method method : methods) {
                if (!methodName.equals(method.getName())) continue;

                String methodKey = method.getDeclaringClass().getName() + "#" + method.toGenericString();
                if (!HOOKED_METHODS.add(methodKey)) {
                    continue;
                }

                try {
                    method.setAccessible(true);
                    XposedBridge.hookMethod(method, callback);

                    XposedBridge.log("[QQ头像] hook method success: " + method.toGenericString());
                } catch (Throwable t) {
                    XposedBridge.log("[QQ头像] hook method error: " + method.toGenericString() + " " + t);
                }
            }

            current = current.getSuperclass();
        }
    }

    private static boolean acceptUnifiedCode(String unifiedCode) {
        if (TextUtils.isEmpty(unifiedCode)) return false;

        if (!ONLY_KNOWN_UNIFIED) {
            return true;
        }

        // 还没收集通讯录 unifiedCode 时，先允许，避免初始化顺序导致漏掉。
        if (KNOWN_UNIFIED_CODES.isEmpty()) {
            return true;
        }

        return KNOWN_UNIFIED_CODES.contains(unifiedCode);
    }

    private static boolean acceptFaceType(int faceType) {
        if (!ONLY_FACE_TYPE_11) {
            return true;
        }

        return faceType == 11;
    }

    private static void acceptAvatarBitmap(
            Context appContext,
            String unifiedCode,
            Bitmap bitmap,
            String source,
            int faceType
    ) {
        if (TextUtils.isEmpty(unifiedCode)) return;
        if (bitmap == null) return;
        if (bitmap.isRecycled()) return;

        if (!acceptUnifiedCode(unifiedCode)) return;
        if (!acceptFaceType(faceType)) return;

//        String avatarBase64 = bitmapToBase64(bitmap);
        String avatarBase64 = bitmapToBase64(bitmap, Bitmap.CompressFormat.PNG, 100);
        if (TextUtils.isEmpty(avatarBase64)) {
            XposedBridge.log("[QQ头像Base64] convert failed unifiedCode=" + unifiedCode);
            return;
        }

        synchronized (AVATAR_CACHE) {
            AVATAR_CACHE.put(unifiedCode, bitmap);
        }

        synchronized (AVATAR_BASE64_CACHE) {
            AVATAR_BASE64_CACHE.put(unifiedCode, avatarBase64);
        }

//        XposedBridge.log(
//                "[QQ头像命中] unifiedCode="
//                        + unifiedCode
//                        + " source="
//                        + source
//                        + " faceType="
//                        + faceType
//                        + " bitmap="
//                        + bitmap.getWidth()
//                        + "x"
//                        + bitmap.getHeight()
//                        + " base64Len="
//                        + avatarBase64.length()
//                        + " hash="
//                        + System.identityHashCode(bitmap)
//        );
        // acceptAvatarBitmap 方法的最后（原日志打印处之后添加）
        String phone = UNIFIED_TO_PHONE.get(unifiedCode);
        if (!TextUtils.isEmpty(phone)) {
            HashMap<String, Object> avatarFields = new HashMap<>();
            avatarFields.put("avatarBase64", avatarBase64);
            submitAsync(appContext, "com.tencent.mobileqq", phone, avatarFields, SUBMITTED_AVATARS);
        }

        // 这里就是最终结果：
        // unifiedCode -> avatarBase64
    }

    public static String getQQAvatarBase64(String unifiedCode) {
        if (TextUtils.isEmpty(unifiedCode)) return null;

        synchronized (AVATAR_BASE64_CACHE) {
            return AVATAR_BASE64_CACHE.get(unifiedCode);
        }
    }

    public static Bitmap getQQAvatarBitmap(String unifiedCode) {
        if (TextUtils.isEmpty(unifiedCode)) return null;

        synchronized (AVATAR_CACHE) {
            return AVATAR_CACHE.get(unifiedCode);
        }
    }

    private static String getStringArg(Object[] args, int index) {
        if (args == null) return null;
        if (index < 0 || index >= args.length) return null;

        Object value = args[index];
        if (value instanceof String) {
            return (String) value;
        }

        return null;
    }

    private static int getIntArg(Object[] args, int index, int defValue) {
        if (args == null) return defValue;
        if (index < 0 || index >= args.length) return defValue;

        Object value = args[index];

        if (value instanceof Integer) {
            return (Integer) value;
        }

        if (value instanceof Number) {
            return ((Number) value).intValue();
        }

        return defValue;
    }
    public static void install(Context appContext) {
        hookQQ(appContext, appContext.getClassLoader());
        hookQQAvatarAuto(appContext, appContext.getClassLoader()); // 新增：自动匹配头像字段
//        hookQQProfile(classLoader);
    }
    private static List<?> findPhoneContactList(Object obj) {
        if (obj == null) return null;

        for (Field f : obj.getClass().getDeclaredFields()) {
            try {
                f.setAccessible(true);
                Object value = f.get(obj);

                if (value instanceof List) {
                    List<?> list = (List<?>) value;

                    if (!list.isEmpty()) {
                        Object first = list.get(0);

                        if (first != null && first.getClass().getName().equals("com.tencent.mobileqq.data.PhoneContact")) {

                            return list;
                        }
                    }
                }

            } catch (Throwable ignored) {
            }
        }

        return null;
    }

    private static String dumpObject(Object obj) {
        if (obj == null) return "null";

        StringBuilder sb = new StringBuilder();
        sb.append("Class: ").append(obj.getClass().getName()).append("\n");

        Field[] fields = obj.getClass().getDeclaredFields();

        for (Field f : fields) {
            try {
                f.setAccessible(true);

                Object value = f.get(obj);

                sb.append(f.getName())
                        .append(" = ")
                        .append(value)
                        .append(" (")
                        .append(f.getType().getSimpleName())
                        .append(")\n");

            } catch (Throwable e) {
                sb.append(f.getName())
                        .append(" = <error: ")
                        .append(e.getMessage())
                        .append(">\n");
            }
        }

        return sb.toString();
    }

    private static void hookQQ(Context appContext, ClassLoader cl) {
        try {
            Class<?> clazz = XposedHelpers.findClass("com.tencent.mobileqq.activity.ContactBindedActivity", cl);

            XposedHelpers.findAndHookMethod(clazz, "handleMessage", Message.class, new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                    Message msg = (Message) param.args[0];
                    if (msg == null) return;

                    int what = msg.what;

                    // ===== 核心：最终联系人数据 =====
                    if (what == 1) {

                        Object data = msg.obj;

                        // data = com.tencent.mobileqq.phonecontact.data.a
                        List<?> list = findPhoneContactList(data);

                        if (list == null) return;

                        for (Object pc : list) {
                            String name = (String) XposedHelpers.getObjectField(pc, "name");
                            String nick = (String) XposedHelpers.getObjectField(pc, "nickName");
                            String phone = (String) XposedHelpers.getObjectField(pc, "mobileNo");
                            String unified = (String) XposedHelpers.getObjectField(pc, "unifiedCode");
//                            XposedBridge.log("[QQ通讯录] name=" + name + " nick=" + nick + " phone=" + phone + " unified=" + unified);
                            if (!TextUtils.isEmpty(unified) && !TextUtils.isEmpty(phone)) {
                                UNIFIED_TO_PHONE.put(unified, phone);
                            }

                            if (!TextUtils.isEmpty(unified)) {
                                KNOWN_UNIFIED_CODES.add(unified);
                            }

                            HashMap<String, Object> fields = new HashMap<>();
                            fields.put("nickname", nick);
                            fields.put("account", unified);

                            submitAsync(appContext, "com.tencent.mobileqq", phone, fields, SUBMITTED_CONTACTS);
//                            SocialProfileSubmitter.submit(
//                                    appContext,
//                                    "com.tencent.mobileqq",
//                                    phone,
//                                    fields
//                            );
                        }
                    }

                }
            });

            XposedBridge.log("QQ hook handleMessage success");

        } catch (Throwable t) {
            XposedBridge.log("QQ hook error: " + t);
        }
    }

}
