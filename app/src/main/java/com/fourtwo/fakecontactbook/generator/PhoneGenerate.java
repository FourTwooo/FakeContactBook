package com.fourtwo.fakecontactbook.generator;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class PhoneGenerate {
    private PhoneRangeProvider apiProvider;
    private PhoneRangeProvider dbProvider;

    /**
     * 对应 Python 里的 is_db。
     *
     * Android 里建议：
     * 没放 area_code.db 时设 false；
     * 放了 area_code.db 后再设 true。
     */
    public boolean isDb = false;

    public PhoneGenerate() {
        this.apiProvider = (incompletePhone, cityName, isp) ->
                PhonePrefixApi.telPhone(incompletePhone, cityName, isp);
    }

    public void setApiProvider(PhoneRangeProvider apiProvider) {
        this.apiProvider = apiProvider;
    }

    public void setDbProvider(PhoneRangeProvider dbProvider) {
        this.dbProvider = dbProvider;
    }

    public ArrayList<String> getPhone(String incompletePhone, String cityName, String isp) throws Exception {
        return getPhone(incompletePhone, cityName, isp, 0);
    }

    /**
     * @param maxResults 0 表示不限制。Android UI 建议设置上限，避免一次生成几百万个导致 OOM。
     */
    public ArrayList<String> getPhone(String incompletePhone, String cityName, String isp, int maxResults) throws Exception {
        validateIncompletePhone(incompletePhone);

        ArrayList<String> phoneRanges = generatePhoneArea(incompletePhone, cityName, isp);

        if (phoneRanges.isEmpty()) {
            throw new IllegalStateException(
                    (cityName == null ? "" : cityName)
                            + " "
                            + incompletePhone
                            + " 未查询到符合号段"
            );
        }

        LinkedHashSet<String> result = new LinkedHashSet<>();

        for (String range : phoneRanges) {
            if (TextUtils.isEmpty(range)) continue;

            String sevenRange = range.length() >= 7 ? range.substring(0, 7) : range;
            generateCompletePhones(sevenRange, incompletePhone, result, maxResults);

            if (maxResults > 0 && result.size() >= maxResults) {
                break;
            }
        }

        return new ArrayList<>(result);
    }

    public ArrayList<String> generatePhoneArea(String incompletePhone, String cityName, String isp) throws Exception {
        validateIncompletePhone(incompletePhone);

        List<String> phoneRangeList;

        if (isDb) {
            if (dbProvider == null) {
                throw new IllegalStateException("isDb=true 但没有设置 dbProvider");
            }

            phoneRangeList = dbProvider.getPhoneRanges(incompletePhone, cityName, isp);
        } else {
            if (!TextUtils.isEmpty(cityName)) {
                if (apiProvider == null) {
                    throw new IllegalStateException("cityName 不为空，但没有设置 apiProvider");
                }

                phoneRangeList = apiProvider.getPhoneRanges(incompletePhone, cityName, isp);
            } else {
                phoneRangeList = generateLocalRangesWithoutCity(incompletePhone, isp);
            }
        }

        ArrayList<String> filtered = new ArrayList<>();

        if (phoneRangeList == null) {
            return filtered;
        }

        for (String range : phoneRangeList) {
            if (TextUtils.isEmpty(range) || range.length() < 7) {
                continue;
            }

            String sevenRange = range.substring(0, 7);

            if (rangeMatchesIncompletePhone(sevenRange, incompletePhone)) {
                filtered.add(sevenRange);
            }
        }

        return filtered;
    }

    public static ArrayList<String> generateCompletePhones(String mobilePhoneNumberRange, String incompletePhone) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        generateCompletePhones(mobilePhoneNumberRange, incompletePhone, result, 0);
        return new ArrayList<>(result);
    }

    private static void generateCompletePhones(
            String mobilePhoneNumberRange,
            String incompletePhone,
            LinkedHashSet<String> output,
            int maxResults
    ) {
        if (TextUtils.isEmpty(mobilePhoneNumberRange) || mobilePhoneNumberRange.length() < 7) {
            return;
        }

        String template = mobilePhoneNumberRange.substring(0, 7) + incompletePhone.substring(7, 11);
        char[] chars = template.toCharArray();

        fillStar(chars, 0, output, maxResults);
    }

    private static boolean fillStar(char[] chars, int start, LinkedHashSet<String> output, int maxResults) {
        if (maxResults > 0 && output.size() >= maxResults) {
            return false;
        }

        int starIndex = -1;

        for (int i = start; i < chars.length; i++) {
            if (chars[i] == '*') {
                starIndex = i;
                break;
            }
        }

        if (starIndex == -1) {
            output.add(new String(chars));
            return maxResults <= 0 || output.size() < maxResults;
        }

        for (char c = '0'; c <= '9'; c++) {
            chars[starIndex] = c;

            boolean shouldContinue = fillStar(chars, starIndex + 1, output, maxResults);
            if (!shouldContinue) {
                chars[starIndex] = '*';
                return false;
            }
        }

        chars[starIndex] = '*';
        return true;
    }

    private ArrayList<String> generateLocalRangesWithoutCity(String incompletePhone, String isp) {
        String segment = incompletePhone.substring(0, 3);
        ArrayList<String> prefixes;

        if (segment.contains("*")) {
            prefixes = PhoneIspCodes.getCodesByCarrierAndSegment(isp, segment);
        } else {
            prefixes = new ArrayList<>();
            prefixes.add(segment);
        }

        if (prefixes.isEmpty()) {
            prefixes = PhoneIspCodes.getPrefixesByCarrier(isp);
        }

        ArrayList<String> result = new ArrayList<>();

        for (String prefix : prefixes) {
            for (int i = 0; i <= 9999; i++) {
                result.add(prefix + String.format("%04d", i));
            }
        }

        return result;
    }

    private static boolean rangeMatchesIncompletePhone(String sevenRange, String incompletePhone) {
        if (sevenRange == null || sevenRange.length() < 7) {
            return false;
        }

        for (int i = 0; i < 7; i++) {
            char mask = incompletePhone.charAt(i);

            if (mask != '*' && mask != sevenRange.charAt(i)) {
                return false;
            }
        }

        return true;
    }

    private static void validateIncompletePhone(String incompletePhone) {
        if (TextUtils.isEmpty(incompletePhone)) {
            throw new IllegalArgumentException("手机号模板不能为空");
        }

        if (incompletePhone.length() != 11) {
            throw new IllegalArgumentException("手机号模板长度必须是 11 位");
        }

        for (int i = 0; i < incompletePhone.length(); i++) {
            char c = incompletePhone.charAt(i);

            if (c != '*' && (c < '0' || c > '9')) {
                throw new IllegalArgumentException("手机号模板只能包含数字和 *");
            }
        }

        if (incompletePhone.charAt(0) != '1' && incompletePhone.charAt(0) != '*') {
            throw new IllegalArgumentException("手机号第一位应该是 1 或 *");
        }
    }
}