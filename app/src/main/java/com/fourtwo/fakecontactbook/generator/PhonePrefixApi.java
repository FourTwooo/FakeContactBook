package com.fourtwo.fakecontactbook.generator;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PhonePrefixApi {
    private PhonePrefixApi() {
    }

    public static ArrayList<String> telPhone(String incompletePhone, String cityName, String isp) throws Exception {
        ArrayList<String> phoneCodes = resolvePhoneCodes(incompletePhone, isp);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String phoneCode : phoneCodes) {
            URL url = new URI(
                    "https",
                    "telphone.cn",
                    "/prefix/" + cityName + phoneCode + "/",
                    null
            ).toURL();

            String html = get(url);
            Matcher matcher = Pattern.compile("(\\d{7})号段").matcher(html);

            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        }

        return new ArrayList<>(result);
    }

    public static ArrayList<String> chaHaoBa(String incompletePhone, String cityName, String isp) throws Exception {
        ArrayList<String> phoneCodes = resolvePhoneCodes(incompletePhone, isp);
        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String phoneCode : phoneCodes) {
            URL url = new URI(
                    "https",
                    "www.chahaoba.com",
                    "/" + cityName + phoneCode,
                    null
            ).toURL();

            String html = get(url);
            Matcher matcher = Pattern.compile("title=\"([0-9]{7})\"").matcher(html);

            while (matcher.find()) {
                result.add(matcher.group(1));
            }
        }

        return new ArrayList<>(result);
    }

    private static ArrayList<String> resolvePhoneCodes(String incompletePhone, String isp) {
        String segment = "";

        if (!TextUtils.isEmpty(incompletePhone) && incompletePhone.length() >= 3) {
            segment = incompletePhone.substring(0, 3);
        }

        if (TextUtils.isEmpty(segment)) {
            return PhoneIspCodes.getPrefixesByCarrier(isp);
        }

        if (segment.contains("*")) {
            return PhoneIspCodes.getCodesByCarrierAndSegment(isp, segment);
        }

        ArrayList<String> result = new ArrayList<>();
        result.add(segment);
        return result;
    }

    private static String get(URL url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) FakeContactBook/1.0");
        connection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");

        int code = connection.getResponseCode();
        InputStream inputStream = code >= 200 && code < 300
                ? connection.getInputStream()
                : connection.getErrorStream();

        if (inputStream == null) {
            throw new IllegalStateException("HTTP " + code + " empty response");
        }

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        } finally {
            connection.disconnect();
        }

        return builder.toString();
    }
}