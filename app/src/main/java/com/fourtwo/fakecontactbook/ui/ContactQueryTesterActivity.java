package com.fourtwo.fakecontactbook.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.fakecontactbook.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ContactQueryTesterActivity extends AppCompatActivity {
    private static final int REQ_READ_CONTACTS = 3101;

    /*
     * 必须和 xposed_hook.java 里的 TEST_CALLER_PACKAGE_PARAM 保持一致。
     */
    private static final String TEST_CALLER_PACKAGE_PARAM = "_fcb_test_pkg";

    private Spinner spinnerQueryType;
    private TextInputEditText inputTestPackage;
    private TextInputEditText inputLookupNumber;
    private TextView textStatus;
    private TextView textResult;
    private MaterialButton btnRunQuery;
    private MaterialButton btnClear;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final String[] queryTypes = new String[]{
            "手机号列表 Phone.CONTENT_URI",
            "联系人列表 Contacts.CONTENT_URI",
            "Data 表 Data.CONTENT_URI",
            "号码反查 PhoneLookup"
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_contact_query_tester);

        bindViews();
        setupToolbar();
        setupSpinner();
        setupButtons();

        textStatus.setText("用于验证 Xposed Provider 模式返回结果。测试包名可填目标 App 包名，例如 com.eg.android.AlipayGphone。");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindViews() {
        spinnerQueryType = findViewById(R.id.spinnerQueryType);
        inputTestPackage = findViewById(R.id.inputTestPackage);
        inputLookupNumber = findViewById(R.id.inputLookupNumber);
        textStatus = findViewById(R.id.textQueryStatus);
        textResult = findViewById(R.id.textQueryResult);
        btnRunQuery = findViewById(R.id.btnRunContactQuery);
        btnClear = findViewById(R.id.btnClearQueryResult);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupSpinner() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                queryTypes
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerQueryType.setAdapter(adapter);
    }

    private void setupButtons() {
        btnRunQuery.setOnClickListener(v -> runQueryWithPermissionCheck());

        btnClear.setOnClickListener(v -> {
            textStatus.setText("已清空结果");
            textResult.setText("");
        });
    }

    private void runQueryWithPermissionCheck() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQ_READ_CONTACTS);
            return;
        }

        runQuery();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode,
            String[] permissions,
            int[] grantResults
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQ_READ_CONTACTS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                runQuery();
            } else {
                Toast.makeText(this, "没有 READ_CONTACTS 权限，无法查询通讯录", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void runQuery() {
        QuerySpec spec = buildQuerySpec();

        if (spec == null) {
            return;
        }

        String testPackage = getText(inputTestPackage);

        if (!TextUtils.isEmpty(testPackage) && !looksLikePackageName(testPackage)) {
            inputTestPackage.setError("包名格式不正确");
            return;
        }

        if (!TextUtils.isEmpty(testPackage)) {
            spec.uri = spec.uri.buildUpon()
                    .appendQueryParameter(TEST_CALLER_PACKAGE_PARAM, testPackage)
                    .build();
        }

        textStatus.setText("正在查询...");
        textResult.setText("");

        executor.execute(() -> {
            String result;

            try {
                result = executeQuery(spec, testPackage);
            } catch (Throwable throwable) {
                result = "查询失败：\n" + throwable;
            }

            String finalResult = result;

            runOnUiThread(() -> {
                textStatus.setText("查询完成");
                textResult.setText(finalResult);
            });
        });
    }

    private QuerySpec buildQuerySpec() {
        int type = spinnerQueryType.getSelectedItemPosition();

        QuerySpec spec = new QuerySpec();

        if (type == 0) {
            spec.title = "手机号列表";
            spec.uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;

            /*
             * 不指定 projection。
             * 让当前 ROM 的 ContactsProvider 自己决定返回哪些列。
             * 避免 display_name_primary / normalized_number 等列在某些 ROM/URI 下被判定为 invalid。
             */
            spec.projection = null;
            return spec;
        }

        if (type == 1) {
            spec.title = "联系人列表";
            spec.uri = ContactsContract.Contacts.CONTENT_URI;
            spec.projection = null;
            return spec;
        }

        if (type == 2) {
            spec.title = "Data 表";
            spec.uri = ContactsContract.Data.CONTENT_URI;
            spec.projection = null;
            return spec;
        }

        if (type == 3) {
            String number = getText(inputLookupNumber);

            if (TextUtils.isEmpty(number)) {
                inputLookupNumber.setError("号码反查需要填写手机号");
                return null;
            }

            spec.title = "号码反查";
            spec.uri = Uri.withAppendedPath(
                    ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                    Uri.encode(number)
            );
            spec.projection = null;
            return spec;
        }

        return null;
    }

    private String executeQuery(QuerySpec spec, String testPackage) {
        StringBuilder builder = new StringBuilder();

        builder.append("查询类型：").append(spec.title).append('\n');
        builder.append("测试包名：").append(TextUtils.isEmpty(testPackage) ? "未填写，使用宿主 App 自身身份" : testPackage).append('\n');
        builder.append("URI：").append(spec.uri).append('\n');
        builder.append("Projection：")
                .append(spec.projection == null ? "null（使用系统默认列）" : Arrays.toString(spec.projection))
                .append("\n\n");
        Cursor cursor = null;

        try {
            cursor = getContentResolver().query(
                    spec.uri,
                    spec.projection,
                    null,
                    null,
                    null
            );

            if (cursor == null) {
                builder.append("Cursor = null\n");
                return builder.toString();
            }

            String[] columns = cursor.getColumnNames();
            int count = cursor.getCount();

            builder.append("Cursor count：").append(count).append('\n');
            builder.append("Columns：").append(Arrays.toString(columns)).append("\n\n");

            BundlePrinter.appendExtras(builder, cursor);

            int rowLimit = 200;
            int row = 0;

            while (cursor.moveToNext() && row < rowLimit) {
                builder.append("----- Row ").append(row + 1).append(" -----\n");

                for (int i = 0; i < columns.length; i++) {
                    builder.append(columns[i])
                            .append(" = ")
                            .append(readCursorValue(cursor, i))
                            .append('\n');
                }

                builder.append('\n');
                row++;
            }

            if (count > rowLimit) {
                builder.append("... 还有 ")
                        .append(count - rowLimit)
                        .append(" 行未显示\n");
            }
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }

        return builder.toString();
    }

    private String readCursorValue(Cursor cursor, int columnIndex) {
        try {
            int type = cursor.getType(columnIndex);

            switch (type) {
                case Cursor.FIELD_TYPE_NULL:
                    return "null";

                case Cursor.FIELD_TYPE_INTEGER:
                    return String.valueOf(cursor.getLong(columnIndex));

                case Cursor.FIELD_TYPE_FLOAT:
                    return String.valueOf(cursor.getDouble(columnIndex));

                case Cursor.FIELD_TYPE_BLOB:
                    byte[] bytes = cursor.getBlob(columnIndex);
                    return bytes == null ? "<blob null>" : "<blob length=" + bytes.length + ">";

                case Cursor.FIELD_TYPE_STRING:
                default:
                    String value = cursor.getString(columnIndex);
                    return value == null ? "null" : value;
            }
        } catch (Throwable throwable) {
            return "<读取失败：" + throwable.getClass().getSimpleName() + ">";
        }
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }

    private boolean looksLikePackageName(String value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }

        if (!value.contains(".")) {
            return false;
        }

        return !value.contains(" ")
                && !value.contains("=")
                && !value.contains("?")
                && !value.contains("/")
                && !value.contains(":");
    }

    private static class QuerySpec {
        String title;
        Uri uri;
        String[] projection;
    }

    private static class BundlePrinter {
        static void appendExtras(StringBuilder builder, Cursor cursor) {
            try {
                Bundle extras = cursor.getExtras();

                if (extras == null || extras.isEmpty()) {
                    builder.append("Extras：空\n\n");
                    return;
                }

                builder.append("Extras：\n");

                for (String key : extras.keySet()) {
                    Object value = extras.get(key);

                    if (value instanceof String[]) {
                        builder.append("  ")
                                .append(key)
                                .append(" = ")
                                .append(Arrays.toString((String[]) value))
                                .append('\n');
                    } else if (value instanceof int[]) {
                        builder.append("  ")
                                .append(key)
                                .append(" = ")
                                .append(Arrays.toString((int[]) value))
                                .append('\n');
                    } else {
                        builder.append("  ")
                                .append(key)
                                .append(" = ")
                                .append(String.valueOf(value))
                                .append('\n');
                    }
                }

                builder.append('\n');
            } catch (Throwable throwable) {
                builder.append("Extras 读取失败：").append(throwable).append("\n\n");
            }
        }
    }
}