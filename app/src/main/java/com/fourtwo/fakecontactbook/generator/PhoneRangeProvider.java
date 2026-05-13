package com.fourtwo.fakecontactbook.generator;

import java.util.List;

public interface PhoneRangeProvider {
    /**
     * 返回 7 位手机号段。
     *
     * 例如：
     * 1830971
     * 1380013
     */
    List<String> getPhoneRanges(String incompletePhone, String cityName, String isp) throws Exception;
}