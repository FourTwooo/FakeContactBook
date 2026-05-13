package com.fourtwo.fakecontactbook.generator;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public class PhoneCodeDatabaseProvider implements PhoneRangeProvider {
    private static final String ASSET_DB_NAME = "area_code.db";

    private final Context context;

    public PhoneCodeDatabaseProvider(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public List<String> getPhoneRanges(String incompletePhone, String cityName, String isp) throws Exception {
        File dbFile = ensureDatabaseCopied();
        SQLiteDatabase db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);

        try {
            LinkedHashSet<String> result = new LinkedHashSet<>();

            queryPhoneCodes(db, result, incompletePhone, cityName, true, isp);

            if (result.isEmpty() && !TextUtils.isEmpty(cityName)) {
                queryPhoneCodes(db, result, incompletePhone, cityName, false, isp);
            }

            return new ArrayList<>(result);
        } finally {
            db.close();
        }
    }

    private void queryPhoneCodes(
            SQLiteDatabase db,
            LinkedHashSet<String> result,
            String incompletePhone,
            String cityName,
            boolean cityMode,
            String isp
    ) {
        ArrayList<String> where = new ArrayList<>();
        ArrayList<String> args = new ArrayList<>();

        if (!TextUtils.isEmpty(incompletePhone)) {
            String value = incompletePhone.length() >= 7 ? incompletePhone.substring(0, 7) : incompletePhone;
            value = value.replace("*", "%");

            where.add("`号段` LIKE ?");
            args.add(value);
        }

        if (!TextUtils.isEmpty(cityName)) {
            where.add(cityMode ? "`市` LIKE ?" : "`省` LIKE ?");
            args.add("%" + cityName + "%");
        }

        if (!TextUtils.isEmpty(isp) && !"不限".equals(isp)) {
            where.add("`运营商` LIKE ?");
            args.add("%" + isp + "%");
        }

        StringBuilder sql = new StringBuilder("SELECT * FROM phone_data");

        if (!where.isEmpty()) {
            sql.append(" WHERE ");

            for (int i = 0; i < where.size(); i++) {
                if (i > 0) sql.append(" AND ");
                sql.append(where.get(i));
            }
        }

        Cursor cursor = null;

        try {
            cursor = db.rawQuery(sql.toString(), args.toArray(new String[0]));

            while (cursor.moveToNext()) {
                String range = cursor.getString(0);

                if (!TextUtils.isEmpty(range) && range.length() >= 7) {
                    result.add(range.substring(0, 7));
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private File ensureDatabaseCopied() throws Exception {
        File dir = context.getDatabasePath(ASSET_DB_NAME).getParentFile();

        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }

        File target = context.getDatabasePath(ASSET_DB_NAME);

        if (target.exists() && target.length() > 0) {
            return target;
        }

        try (InputStream input = context.getAssets().open(ASSET_DB_NAME);
             FileOutputStream output = new FileOutputStream(target)) {

            byte[] buffer = new byte[8192];
            int read;

            while ((read = input.read(buffer)) > 0) {
                output.write(buffer, 0, read);
            }
        }

        return target;
    }
}