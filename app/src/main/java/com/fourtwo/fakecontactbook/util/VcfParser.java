package com.fourtwo.fakecontactbook.util;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.fourtwo.fakecontactbook.model.FakeContact;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

public final class VcfParser {
    private VcfParser() {
    }

    public static ArrayList<FakeContact> parse(Context context, Uri uri) throws IOException {
        ArrayList<String> lines = readUnfoldedLines(context, uri);
        ArrayList<FakeContact> contacts = new ArrayList<>();

        boolean inCard = false;
        String name = "";
        String email = "";
        ArrayList<String> phones = new ArrayList<>();

        for (String rawLine : lines) {
            if (rawLine == null) continue;
            String line = rawLine.trim();
            String upper = line.toUpperCase(Locale.US);

            if (upper.startsWith("BEGIN:VCARD")) {
                inCard = true;
                name = "";
                email = "";
                phones.clear();
                continue;
            }
            if (!inCard) continue;

            if (upper.startsWith("END:VCARD")) {
                if (phones.isEmpty() && !TextUtils.isEmpty(name)) {
                    // 没有手机号的联系人对大多数读取通讯录手机号的 App 没意义，这里跳过。
                } else {
                    for (String phone : phones) {
                        String cleanPhone = normalizePhone(phone);
                        if (!TextUtils.isEmpty(cleanPhone)) {
                            contacts.add(new FakeContact(TextUtils.isEmpty(name) ? cleanPhone : name, cleanPhone, email));
                        }
                    }
                }
                inCard = false;
                continue;
            }

            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon);
            String value = decodeValue(key, line.substring(colon + 1));
            String keyUpper = key.toUpperCase(Locale.US);

            if (keyUpper.startsWith("FN")) {
                name = value;
            } else if (TextUtils.isEmpty(name) && keyUpper.startsWith("N")) {
                name = value.replace(';', ' ').trim();
            } else if (keyUpper.startsWith("TEL")) {
                phones.add(value);
            } else if (keyUpper.startsWith("EMAIL")) {
                email = value;
            }
        }
        return contacts;
    }

    private static ArrayList<String> readUnfoldedLines(Context context, Uri uri) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        InputStream inputStream = context.getContentResolver().openInputStream(uri);
        if (inputStream == null) return result;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            StringBuilder current = new StringBuilder();
            while ((line = reader.readLine()) != null) {
                if ((line.startsWith(" ") || line.startsWith("\t")) && current.length() > 0) {
                    current.append(line.substring(1));
                } else {
                    if (current.length() > 0) {
                        result.add(current.toString());
                    }
                    current.setLength(0);
                    current.append(line);
                }
            }
            if (current.length() > 0) {
                result.add(current.toString());
            }
        }
        return result;
    }

    private static String decodeValue(String key, String value) {
        String decoded = value == null ? "" : value;
        decoded = decoded.replace("\\n", " ")
                .replace("\\N", " ")
                .replace("\\,", ",")
                .replace("\\;", ";")
                .trim();

        String upperKey = key.toUpperCase(Locale.US);
        if (upperKey.contains("QUOTED-PRINTABLE")) {
            Charset charset = StandardCharsets.UTF_8;
            int charsetIndex = upperKey.indexOf("CHARSET=");
            if (charsetIndex >= 0) {
                String charsetName = key.substring(charsetIndex + "CHARSET=".length());
                int semicolon = charsetName.indexOf(';');
                if (semicolon >= 0) charsetName = charsetName.substring(0, semicolon);
                try {
                    charset = Charset.forName(charsetName.trim());
                } catch (Exception ignored) {
                    charset = StandardCharsets.UTF_8;
                }
            }
            decoded = quotedPrintableToString(decoded, charset);
        }
        return decoded.trim();
    }

    private static String quotedPrintableToString(String value, Charset charset) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '=' && i + 2 < value.length()) {
                String hex = value.substring(i + 1, i + 3);
                try {
                    output.write(Integer.parseInt(hex, 16));
                    i += 2;
                    continue;
                } catch (Exception ignored) {
                }
            }
            output.write((byte) c);
        }
        return new String(output.toByteArray(), charset);
    }

    private static String normalizePhone(String phone) {
        if (phone == null) return "";
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
}
