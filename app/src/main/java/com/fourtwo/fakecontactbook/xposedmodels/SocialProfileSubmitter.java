package com.fourtwo.fakecontactbook.xposedmodels;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Map;

import de.robv.android.xposed.XposedBridge;

public class SocialProfileSubmitter {
    private static final String HOST_SOCIAL_URI = "content://com.fourtwo.fakecontactbook.provider/social";
    private static final String METHOD_UPSERT_SOCIAL_PROFILE = "upsertSocialProfile";
    private static final String EXTRA_PAYLOAD_JSON = "payloadJson";

    public static boolean submit(
            Context context,
            String platformPackageName,
            String phone,
            Map<String, Object> fields
    ) {
        if (context == null) {
            return false;
        }

        if (TextUtils.isEmpty(platformPackageName) || TextUtils.isEmpty(phone)) {
            return false;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("packageName", platformPackageName);
            payload.put("phone", phone);

            if (fields != null) {
                for (Map.Entry<String, Object> entry : fields.entrySet()) {
                    if (entry == null || TextUtils.isEmpty(entry.getKey())) {
                        continue;
                    }

                    Object value = entry.getValue();
                    if (value == null) {
                        continue;
                    }

                    payload.put(entry.getKey(), value);
                }
            }

            Bundle extras = new Bundle();
            extras.putString(EXTRA_PAYLOAD_JSON, payload.toString());

            Bundle result = context.getContentResolver().call(
                    Uri.parse(HOST_SOCIAL_URI),
                    METHOD_UPSERT_SOCIAL_PROFILE,
                    null,
                    extras
            );

            if (result == null) {
                XposedBridge.log("FakeContactBook social submit failed: null result");
                return false;
            }

            boolean ok = result.getBoolean("ok", false);
            String reason = result.getString("reason", "");
            int matchedCount = result.getInt("matchedCount", 0);

            XposedBridge.log(
                    "FakeContactBook social submit result: ok="
                            + ok
                            + ", reason="
                            + reason
                            + ", matchedCount="
                            + matchedCount
            );

            return ok;
        } catch (Throwable throwable) {
            XposedBridge.log("FakeContactBook social submit error: " + throwable);
            return false;
        }
    }
}