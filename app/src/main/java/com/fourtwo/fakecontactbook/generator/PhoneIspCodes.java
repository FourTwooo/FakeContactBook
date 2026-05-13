package com.fourtwo.fakecontactbook.generator;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class PhoneIspCodes {
    public static final Map<String, List<String>> PHONE_ISP_CODES;

    static {
        LinkedHashMap<String, List<String>> map = new LinkedHashMap<>();

        map.put("移动", Arrays.asList(
                "134", "135", "136", "137", "138", "139", "147", "150", "151", "152", "157", "158", "159",
                "165", "172", "178", "182", "183", "184", "187", "188", "195", "197", "198"
        ));

        map.put("联通", Arrays.asList(
                "130", "131", "132", "145", "146", "155", "156", "166", "167", "171", "175", "176", "185",
                "186", "196"
        ));

        map.put("电信", Arrays.asList(
                "133", "149", "153", "162", "173", "174", "177", "180", "181", "189", "191", "193", "199"
        ));

        PHONE_ISP_CODES = Collections.unmodifiableMap(map);
    }

    private PhoneIspCodes() {
    }

    public static ArrayList<String> getAllPrefixes() {
        ArrayList<String> result = new ArrayList<>();

        for (List<String> list : PHONE_ISP_CODES.values()) {
            result.addAll(list);
        }

        return result;
    }

    public static ArrayList<String> getPrefixesByCarrier(String carrier) {
        ArrayList<String> result = new ArrayList<>();

        if (TextUtils.isEmpty(carrier) || "不限".equals(carrier)) {
            result.addAll(getAllPrefixes());
            return result;
        }

        for (Map.Entry<String, List<String>> entry : PHONE_ISP_CODES.entrySet()) {
            if (entry.getKey().contains(carrier) || carrier.contains(entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }

        return result;
    }

    public static ArrayList<String> getCodesByCarrierAndSegment(String carrier, String segment) {
        ArrayList<String> base = getPrefixesByCarrier(carrier);
        return filterPrefixesBySegment(base, segment);
    }

    public static ArrayList<String> filterPrefixesBySegment(List<String> prefixes, String segment) {
        ArrayList<String> result = new ArrayList<>();

        if (prefixes == null || prefixes.isEmpty()) {
            return result;
        }

        if (TextUtils.isEmpty(segment)) {
            result.addAll(prefixes);
            return result;
        }

        String regex = segment.replace("*", ".*");
        Pattern pattern = Pattern.compile(regex);

        for (String prefix : prefixes) {
            if (pattern.matcher(prefix).matches()) {
                result.add(prefix);
            }
        }

        return result;
    }
}