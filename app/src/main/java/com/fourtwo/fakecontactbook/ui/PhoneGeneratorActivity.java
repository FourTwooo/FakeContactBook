package com.fourtwo.fakecontactbook.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.generator.PhoneCodeDatabaseProvider;
import com.fourtwo.fakecontactbook.generator.PhoneGenerate;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.model.FakeContact;
import com.fourtwo.fakecontactbook.store.ConfigStore;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PhoneGeneratorActivity extends AppCompatActivity {
    private TextInputEditText inputIncompletePhone;
    private TextInputEditText inputCityName;
    private TextInputEditText inputMaxResults;

    private static final int REQ_EXPORT_GENERATED_VCF = 1001;
    private Spinner spinnerIsp;
    private Spinner spinnerProfile;

    private SwitchMaterial switchUseDb;
    private TextView textResult;

    private MaterialButton btnGenerate;
    private MaterialButton btnImport;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final ArrayList<ContactProfile> profiles = new ArrayList<>();
    private final ArrayList<String> generatedPhones = new ArrayList<>();

    private final String[] ispOptions = new String[]{"不限", "移动", "联通", "电信"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_phone_generator);

        ConfigStore.ensureDefaultData(this);

        bindViews();
        setupToolbar();
        setupSpinners();
        setupButtons();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindViews() {
        inputIncompletePhone = findViewById(R.id.inputIncompletePhone);
        inputCityName = findViewById(R.id.inputCityName);
        inputMaxResults = findViewById(R.id.inputMaxResults);

        spinnerIsp = findViewById(R.id.spinnerIsp);
        spinnerProfile = findViewById(R.id.spinnerProfile);

        switchUseDb = findViewById(R.id.switchUseDb);
        textResult = findViewById(R.id.textGeneratorResult);

        btnGenerate = findViewById(R.id.btnGeneratePhone);
        btnImport = findViewById(R.id.btnImportPhones);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupSpinners() {
        ArrayAdapter<String> ispAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                ispOptions
        );
        ispAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerIsp.setAdapter(ispAdapter);

        refreshProfileSpinner();
    }

    private void refreshProfileSpinner() {
        refreshProfileSpinner(getCurrentSelectedProfileId());
    }

    private void refreshProfileSpinner(String preferredProfileId) {
        profiles.clear();
        profiles.addAll(ConfigStore.getProfiles(this));

        ArrayList<String> names = new ArrayList<>();

        for (ContactProfile profile : profiles) {
            int count = profile.contacts == null ? 0 : profile.contacts.size();
            names.add(profile.name + "（" + count + "）");
        }

        if (names.isEmpty()) {
            names.add("请先创建通讯录方案");
        }

        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                names
        );
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        spinnerProfile.setAdapter(profileAdapter);
        spinnerProfile.setEnabled(!profiles.isEmpty());

        if (!profiles.isEmpty()) {
            int selectedIndex = findProfileIndexById(preferredProfileId);
            spinnerProfile.setSelection(Math.max(0, selectedIndex), false);
        }
    }

    private String getCurrentSelectedProfileId() {
        int index = spinnerProfile == null ? -1 : spinnerProfile.getSelectedItemPosition();

        if (index >= 0 && index < profiles.size()) {
            ContactProfile profile = profiles.get(index);
            return profile == null ? "" : profile.id;
        }

        return "";
    }

    private int findProfileIndexById(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return 0;
        }

        for (int i = 0; i < profiles.size(); i++) {
            ContactProfile profile = profiles.get(i);

            if (profile != null && profileId.equals(profile.id)) {
                return i;
            }
        }

        return 0;
    }

    private void setupButtons() {
        btnGenerate.setOnClickListener(v -> generatePhones());

        btnImport.setText("处理生成结果");
        btnImport.setOnClickListener(v -> showGeneratedResultActionDialog());
        btnImport.setEnabled(false);
    }

    private void generatePhones() {
        String incompletePhone = getText(inputIncompletePhone);
        String cityName = getText(inputCityName);
        String isp = String.valueOf(spinnerIsp.getSelectedItem());

        int maxResults = parseMaxResults();

        btnGenerate.setEnabled(false);
        btnImport.setEnabled(false);
        textResult.setText("正在生成，请稍候...");

        executor.execute(() -> {
            try {
                PhoneGenerate generator = new PhoneGenerate();

                generator.isDb = switchUseDb.isChecked();

                if (generator.isDb) {
                    generator.setDbProvider(new PhoneCodeDatabaseProvider(this));
                }

                ArrayList<String> phones = generator.getPhone(
                        incompletePhone,
                        cityName,
                        isp,
                        maxResults
                );

                generatedPhones.clear();
                generatedPhones.addAll(phones);

                runOnUiThread(() -> {
                    btnGenerate.setEnabled(true);
                    btnImport.setEnabled(!generatedPhones.isEmpty());

                    textResult.setText(buildPreviewText(generatedPhones, maxResults));
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnGenerate.setEnabled(true);
                    btnImport.setEnabled(false);
                    textResult.setText("生成失败：" + e.getMessage());
                    Toast.makeText(this, "生成失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showGeneratedResultActionDialog() {
        if (generatedPhones.isEmpty()) {
            Toast.makeText(this, "没有可处理的手机号", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("处理生成结果")
                .setItems(new String[]{"追加到现有方案", "新建方案并导入", "导出 VCF 文件"}, (dialog, which) -> {
                    if (which == 0) {
                        appendGeneratedPhonesToSelectedProfile();
                    } else if (which == 1) {
                        showCreateProfileAndImportDialog();
                    } else {
                        exportGeneratedPhonesToVcf();
                    }
                })
                .show();
    }

    private void appendGeneratedPhonesToSelectedProfile() {
        showAppendToExistingProfileDialog();
    }

    private void showAppendToExistingProfileDialog() {
        if (generatedPhones.isEmpty()) {
            Toast.makeText(this, "没有可导入的手机号", Toast.LENGTH_SHORT).show();
            return;
        }

        final ArrayList<ContactProfile> latestProfiles = ConfigStore.getProfiles(this);

        if (latestProfiles.isEmpty()) {
            Toast.makeText(this, "请先创建通讯录方案，或者选择“新建方案并导入”", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[latestProfiles.size()];

        for (int i = 0; i < latestProfiles.size(); i++) {
            ContactProfile profile = latestProfiles.get(i);
            int count = profile.contacts == null ? 0 : profile.contacts.size();
            names[i] = profile.name + "（" + count + "）";
        }

        String currentSelectedId = getCurrentSelectedProfileId();
        int defaultIndex = 0;

        for (int i = 0; i < latestProfiles.size(); i++) {
            ContactProfile profile = latestProfiles.get(i);

            if (profile != null && currentSelectedId.equals(profile.id)) {
                defaultIndex = i;
                break;
            }
        }

        final int[] selectedIndex = new int[]{defaultIndex};

        new MaterialAlertDialogBuilder(this)
                .setTitle("追加到现有方案")
                .setSingleChoiceItems(names, defaultIndex, (dialog, which) -> selectedIndex[0] = which)
                .setNegativeButton("取消", null)
                .setPositiveButton("追加", (dialog, which) -> {
                    int index = selectedIndex[0];

                    if (index < 0 || index >= latestProfiles.size()) {
                        Toast.makeText(this, "请选择要追加的方案", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    ContactProfile selectedProfile = latestProfiles.get(index);

                    if (selectedProfile == null || TextUtils.isEmpty(selectedProfile.id)) {
                        Toast.makeText(this, "方案无效", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    appendGeneratedPhonesToProfileId(selectedProfile.id);
                })
                .show();
    }

    private void appendGeneratedPhonesToProfileId(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            Toast.makeText(this, "方案 ID 为空", Toast.LENGTH_SHORT).show();
            return;
        }

        ArrayList<String> snapshot = new ArrayList<>(generatedPhones);

        if (snapshot.isEmpty()) {
            Toast.makeText(this, "没有可导入的手机号", Toast.LENGTH_SHORT).show();
            return;
        }

        btnImport.setEnabled(false);

        executor.execute(() -> {
            try {
                ArrayList<ContactProfile> latestProfiles = ConfigStore.getProfiles(this);
                ContactProfile targetProfile = null;

                for (ContactProfile profile : latestProfiles) {
                    if (profile != null && profileId.equals(profile.id)) {
                        targetProfile = profile;
                        break;
                    }
                }

                if (targetProfile == null) {
                    runOnUiThread(() -> {
                        btnImport.setEnabled(!generatedPhones.isEmpty());
                        refreshProfileSpinner();
                        Toast.makeText(this, "目标方案不存在，可能已被删除", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                ImportResult result = importPhonesIntoProfile(targetProfile, snapshot);
                ConfigStore.upsertProfile(this, targetProfile);

                String targetName = targetProfile.name;
                String targetId = targetProfile.id;

                runOnUiThread(() -> {
                    btnImport.setEnabled(!generatedPhones.isEmpty());
                    refreshProfileSpinner(targetId);

                    Toast.makeText(
                            this,
                            "已追加到“" + targetName + "”：导入 "
                                    + result.added
                                    + " 个手机号，跳过 "
                                    + result.skipped
                                    + " 个重复/无效号码",
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnImport.setEnabled(!generatedPhones.isEmpty());
                    Toast.makeText(this, "追加导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void createProfilesAndImportGeneratedPhones(String baseName, int batchLimit) {
        ArrayList<String> snapshot = new ArrayList<>(generatedPhones);

        if (snapshot.isEmpty()) {
            Toast.makeText(this, "没有可导入的手机号", Toast.LENGTH_SHORT).show();
            return;
        }

        btnImport.setEnabled(false);

        executor.execute(() -> {
            try {
                GeneratedBatchImportResult result = buildAndSaveGeneratedPhoneProfiles(
                        baseName,
                        snapshot,
                        batchLimit
                );

                runOnUiThread(() -> {
                    btnImport.setEnabled(!generatedPhones.isEmpty());

                    if (result.added <= 0) {
                        Toast.makeText(
                                this,
                                "没有可导入的有效手机号，跳过 " + result.skipped + " 个重复/无效号码",
                                Toast.LENGTH_LONG
                        ).show();
                        return;
                    }

                    refreshProfileSpinner(result.firstProfileId);

                    Toast.makeText(
                            this,
                            "已创建 " + result.profileCount + " 个方案，导入 "
                                    + result.added + " 个手机号，跳过 "
                                    + result.skipped + " 个重复/无效号码",
                            Toast.LENGTH_LONG
                    ).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    btnImport.setEnabled(!generatedPhones.isEmpty());
                    Toast.makeText(this, "新建方案导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    private GeneratedBatchImportResult buildAndSaveGeneratedPhoneProfiles(
            String baseName,
            ArrayList<String> phones,
            int batchLimit
    ) {
        GeneratedBatchImportResult result = new GeneratedBatchImportResult();

        if (phones == null || phones.isEmpty()) {
            return result;
        }

        HashSet<String> exists = new HashSet<>();
        ArrayList<String> validPhones = new ArrayList<>();

        for (String phone : phones) {
            String normalized = FakeContact.normalizePhone(phone);

            if (TextUtils.isEmpty(normalized)) {
                result.skipped++;
                continue;
            }

            if (exists.contains(normalized)) {
                result.skipped++;
                continue;
            }

            exists.add(normalized);
            validPhones.add(phone);
        }

        if (validPhones.isEmpty()) {
            return result;
        }

        int realBatchSize = batchLimit <= 0 ? validPhones.size() : batchLimit;

        ArrayList<ContactProfile> createdProfiles = new ArrayList<>();

        for (int start = 0; start < validPhones.size(); start += realBatchSize) {
            int end = Math.min(start + realBatchSize, validPhones.size());
            int batchIndex = createdProfiles.size();

            String profileName = batchIndex == 0
                    ? baseName
                    : baseName + "_" + batchIndex;

            ContactProfile profile = new ContactProfile(profileName);

            for (int i = start; i < end; i++) {
                String phone = validPhones.get(i);

                /*
                 * 生成器导入的联系人默认姓名留空。
                 * 通讯录最小有效信息就是手机号。
                 */
                profile.contacts.add(new FakeContact("", phone, ""));
            }

            createdProfiles.add(profile);
        }

        for (ContactProfile profile : createdProfiles) {
            ConfigStore.upsertProfile(this, profile);
        }

        result.added = validPhones.size();
        result.profileCount = createdProfiles.size();
        result.firstProfileId = createdProfiles.isEmpty() ? "" : createdProfiles.get(0).id;

        return result;
    }
    private Integer parseBatchLimit(String value) {
        if (TextUtils.isEmpty(value)) {
            return 2000;
        }

        try {
            int parsed = Integer.parseInt(value);
            return Math.max(parsed, 0);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void selectProfileInSpinner(String profileId) {
        if (TextUtils.isEmpty(profileId)) {
            return;
        }

        for (int i = 0; i < profiles.size(); i++) {
            ContactProfile profile = profiles.get(i);

            if (profile != null && profileId.equals(profile.id)) {
                spinnerProfile.setSelection(i, false);
                return;
            }
        }
    }

    private static class GeneratedBatchImportResult {
        int added;
        int skipped;
        int profileCount;
        String firstProfileId = "";
    }
    private void showCreateProfileAndImportDialog() {
        if (generatedPhones.isEmpty()) {
            Toast.makeText(this, "没有可导入的手机号", Toast.LENGTH_SHORT).show();
            return;
        }

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(18);
        container.setPadding(padding, dp(8), padding, 0);

        EditText inputName = new EditText(this);
        inputName.setHint("方案基础名称，例如：手机号表");
        inputName.setSingleLine(true);
        inputName.setInputType(InputType.TYPE_CLASS_TEXT);
        inputName.setText("手机号表 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date()));

        container.addView(inputName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText inputLimit = new EditText(this);
        inputLimit.setHint("每个方案最多数量，例如：2000");
        inputLimit.setSingleLine(true);
        inputLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputLimit.setText("2000");

        container.addView(inputLimit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView tip = new TextView(this);
        tip.setText("例：生成 5000 个手机号，上限 2000，基础名为“手机号表”，会创建：手机号表、手机号表_1、手机号表_2。填 0 表示不分批。");
        tip.setTextSize(12);
        tip.setPadding(0, dp(8), 0, 0);

        container.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("新建方案并导入")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建并导入", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String baseName = inputName.getText() == null ? "" : inputName.getText().toString().trim();
            String limitText = inputLimit.getText() == null ? "" : inputLimit.getText().toString().trim();

            if (TextUtils.isEmpty(baseName)) {
                inputName.setError("请填写方案名称");
                return;
            }

            Integer batchLimit = parseBatchLimit(limitText);
            if (batchLimit == null) {
                inputLimit.setError("请输入 0 或正整数");
                return;
            }

            dialog.dismiss();
            createProfilesAndImportGeneratedPhones(baseName, batchLimit);
        }));

        dialog.show();
    }

    private ImportResult importPhonesIntoProfile(ContactProfile profile, ArrayList<String> phones) {
        ImportResult result = new ImportResult();

        if (profile.contacts == null) {
            profile.contacts = new ArrayList<>();
        }

        HashSet<String> exists = new HashSet<>();

        for (FakeContact contact : profile.contacts) {
            if (contact == null) continue;

            String normalized = FakeContact.normalizePhone(contact.phone);
            if (!TextUtils.isEmpty(normalized)) {
                exists.add(normalized);
            }
        }

        for (String phone : phones) {
            String normalized = FakeContact.normalizePhone(phone);

            if (TextUtils.isEmpty(normalized)) {
                result.skipped++;
                continue;
            }

            if (exists.contains(normalized)) {
                result.skipped++;
                continue;
            }

            profile.contacts.add(new FakeContact("", phone, ""));
            exists.add(normalized);
            result.added++;
        }

        return result;
    }

    private static class ImportResult {
        int added;
        int skipped;
    }

    private void exportGeneratedPhonesToVcf() {
        if (generatedPhones.isEmpty()) {
            Toast.makeText(this, "没有可导出的手机号", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            String fileName = "generated_phones_"
                    + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(new Date())
                    + ".vcf";

            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("text/x-vcard");
            intent.putExtra(Intent.EXTRA_TITLE, fileName);

            startActivityForResult(intent, REQ_EXPORT_GENERATED_VCF);
        } catch (Exception e) {
            Toast.makeText(this, "打开导出文件选择器失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_EXPORT_GENERATED_VCF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            writeGeneratedPhonesToVcf(data.getData());
        }
    }

    private void writeGeneratedPhonesToVcf(Uri uri) {
        ArrayList<String> snapshot = new ArrayList<>(generatedPhones);

        executor.execute(() -> {
            int written = 0;
            int skipped = 0;
            HashSet<String> exists = new HashSet<>();

            try {
                OutputStream outputStream = getContentResolver().openOutputStream(uri);
                if (outputStream == null) {
                    throw new IllegalStateException("无法打开输出文件");
                }

                try (OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8)) {
                    for (String phone : snapshot) {
                        String normalized = FakeContact.normalizePhone(phone);

                        if (TextUtils.isEmpty(normalized)) {
                            skipped++;
                            continue;
                        }

                        if (exists.contains(normalized)) {
                            skipped++;
                            continue;
                        }

                        exists.add(normalized);
                        writer.write(buildPhoneVcard(phone));
                        written++;
                    }

                    writer.flush();
                }

                int finalWritten = written;
                int finalSkipped = skipped;

                runOnUiThread(() -> Toast.makeText(
                        this,
                        "已导出 " + finalWritten + " 个手机号到 VCF，跳过 " + finalSkipped + " 个重复/无效号码",
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "导出 VCF 失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private String buildPhoneVcard(String phone) {
        String displayName = TextUtils.isEmpty(phone) ? "未命名联系人" : phone;

        return "BEGIN:VCARD\r\n"
                + "VERSION:3.0\r\n"
                + "FN:" + escapeVcf(displayName) + "\r\n"
                + "N:;" + escapeVcf(displayName) + ";;;\r\n"
                + "TEL;TYPE=CELL:" + escapeVcf(phone) + "\r\n"
                + "END:VCARD\r\n";
    }

    private String escapeVcf(String value) {
        if (value == null) return "";

        return value
                .replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("\r", "\\n");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
    private String buildPreviewText(ArrayList<String> phones, int maxResults) {
        StringBuilder builder = new StringBuilder();

        builder.append("生成数量：").append(phones.size()).append("\n");

        if (maxResults > 0 && phones.size() >= maxResults) {
            builder.append("注意：已达到最大生成上限 ").append(maxResults).append("，结果可能被截断。\n");
        }

        builder.append("\n预览：\n");

        int limit = Math.min(80, phones.size());

        for (int i = 0; i < limit; i++) {
            builder.append(phones.get(i)).append('\n');
        }

        if (phones.size() > limit) {
            builder.append("... 还有 ").append(phones.size() - limit).append(" 个");
        }

        return builder.toString();
    }

    private int parseMaxResults() {
        String value = getText(inputMaxResults);

        if (TextUtils.isEmpty(value)) {
            return 0;
        }

        try {
            int parsed = Integer.parseInt(value);
            return Math.max(parsed, 0);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private String getText(TextInputEditText editText) {
        return editText.getText() == null ? "" : editText.getText().toString().trim();
    }
}