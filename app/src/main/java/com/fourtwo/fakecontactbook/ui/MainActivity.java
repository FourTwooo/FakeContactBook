package com.fourtwo.fakecontactbook.ui;

import android.database.Cursor;
import android.provider.ContactsContract;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.model.AppRule;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.model.FakeContact;
import com.fourtwo.fakecontactbook.model.InstalledApp;
import com.fourtwo.fakecontactbook.store.ConfigStore;
import com.fourtwo.fakecontactbook.util.VcfParser;
import com.fourtwo.fakecontactbook.util.UpdateChecker;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQ_IMPORT_VCF = 42;
    private boolean suppressSearchTextChange = false;
    private static final String PROVIDER_HOOK_PROBE_PARAM = "_fcb_provider_probe";
    private static final String PROVIDER_HOOK_PROBE_COLUMN = "fcb_provider_hooked";
    private static final String PROVIDER_HOOK_PROBE_VERSION_COLUMN = "fcb_provider_hook_version";
    private int statsRequestSeq = 0;
    private int globalSpinnerRequestSeq = 0;
    private int profilePageRequestSeq = 0;
    private int appMetaRequestSeq = 0;
    private static final int PAGE_HOME = 0;
    private static final int PAGE_PROFILES = 1;
    private static final int PAGE_APPS = 2;
    private static final int PAGE_EXTENSIONS = 3;

    private View pageHome;
    private View pageList;
    private View pageExtensions;

    private TextView headerTitle;
    private TextView headerSub;
    private TextView textXposedProviderStatusTitle;
    private TextView textXposedProviderStatusSub;
    private MaterialButton btnCheckXposedProviderStatus;
    private TextView textProfileStat;
    private TextView textContactStat;
    private TextView textRuleStat;
    private TextView textSocialStat;

    private TextView sectionTitle;
    private TextView sectionSub;

    private RecyclerView recyclerView;
    private ExtendedFloatingActionButton fab;

    private TextInputLayout searchLayout;
    private TextInputEditText searchInput;

    private SwitchMaterial switchGlobal;
    private Spinner spinnerGlobalProfile;

    private ProfileAdapter profileAdapter;
    private AppRuleAdapter appRuleAdapter;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private int currentPage = PAGE_HOME;
    private boolean suppressGlobalSpinner = false;
    private boolean appsLoaded = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ConfigStore.ensureDefaultData(this);

        bindViews();
        setupAdapters();
        setupGlobalCard();
        setupSearch();
        setupButtons();
        setupBottomNavigation();

        showHome();
        UpdateChecker.checkOnFirstLaunch(this);
        refreshXposedProviderStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshXposedProviderStatus();
        refreshGlobalSpinner();
        refreshHomeStats();

        if (currentPage == PAGE_PROFILES) {
            showProfiles();
        } else if (currentPage == PAGE_APPS) {
            showApps(false);
        } else if (currentPage == PAGE_EXTENSIONS) {
            showExtensions();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindViews() {
        pageHome = findViewById(R.id.pageHome);
        pageList = findViewById(R.id.pageList);
        pageExtensions = findViewById(R.id.pageExtensions);

        headerTitle = findViewById(R.id.headerTitle);
        headerSub = findViewById(R.id.headerSub);

        textProfileStat = findViewById(R.id.textProfileStat);
        textContactStat = findViewById(R.id.textContactStat);
        textRuleStat = findViewById(R.id.textRuleStat);
        textSocialStat = findViewById(R.id.textSocialStat);

        sectionTitle = findViewById(R.id.sectionTitle);
        sectionSub = findViewById(R.id.sectionSub);

        recyclerView = findViewById(R.id.recyclerView);
        fab = findViewById(R.id.fab);

        searchLayout = findViewById(R.id.searchLayout);
        searchInput = findViewById(R.id.searchInput);

        switchGlobal = findViewById(R.id.switchGlobal);
        spinnerGlobalProfile = findViewById(R.id.spinnerGlobalProfile);
        textXposedProviderStatusTitle = findViewById(R.id.textXposedProviderStatusTitle);
        textXposedProviderStatusSub = findViewById(R.id.textXposedProviderStatusSub);
        btnCheckXposedProviderStatus = findViewById(R.id.btnCheckXposedProviderStatus);
    }

    private void setupAdapters() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        recyclerView.setHasFixedSize(true);
        recyclerView.setItemViewCacheSize(12);

        profileAdapter = new ProfileAdapter(new ProfileAdapter.Listener() {
            @Override
            public void onEdit(ContactProfile profile) {
                openEditor(profile.id);
            }

            @Override
            public void onDelete(ContactProfile profile) {
                confirmDeleteProfile(profile);
            }
        });

        appRuleAdapter = new AppRuleAdapter(this);
    }

    private void setupGlobalCard() {
        switchGlobal.setChecked(ConfigStore.isGlobalEnabled(this));
        switchGlobal.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ConfigStore.setGlobalEnabled(this, isChecked);
            refreshHomeStats();

            Toast.makeText(
                    this,
                    isChecked ? "已开启全局假通讯录" : "已关闭全局假通讯录",
                    Toast.LENGTH_SHORT
            ).show();
        });

        refreshGlobalSpinner();
    }

    private void setupButtons() {
//        MaterialButton btnOpenProfiles = findViewById(R.id.btnOpenProfiles);
//        MaterialButton btnOpenApps = findViewById(R.id.btnOpenApps);
//        MaterialButton btnOpenExtensions = findViewById(R.id.btnOpenExtensions);
        MaterialButton btnApiGuide = findViewById(R.id.btnApiGuide);
        MaterialButton btnGeneratorGuide = findViewById(R.id.btnGeneratorGuide);
        MaterialButton btnContactQueryTester = findViewById(R.id.btnContactQueryTester);
        MaterialButton btnHttpApi = findViewById(R.id.btnHttpApi);

//        setClick(btnOpenProfiles, v -> {
//            selectBottomItem(R.id.nav_profiles);
//            showProfiles();
//        });
//
//        setClick(btnOpenApps, v -> {
//            selectBottomItem(R.id.nav_apps);
//            showApps(false);
//        });
//
//        setClick(btnOpenExtensions, v -> {
//            selectBottomItem(R.id.nav_extensions);
//            showExtensions();
//        });

        setClick(btnApiGuide, v -> showApiGuideDialog());
        setClick(btnGeneratorGuide, v -> startActivity(new Intent(this, PhoneGeneratorActivity.class)));
        setClick(btnContactQueryTester, v -> startActivity(new Intent(this, ContactQueryTesterActivity.class)));
        setClick(btnHttpApi, v -> startActivity(new Intent(this, HttpApiActivity.class)));
    }

    private void setClick(View view, View.OnClickListener listener) {
        if (view != null) {
            view.setOnClickListener(listener);
        }
    }

    private void setupBottomNavigation() {
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);

        bottomNavigation.setOnItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.nav_home) {
                if (currentPage != PAGE_HOME) {
                    showHome();
                }
                return true;
            }

            if (id == R.id.nav_profiles) {
                if (currentPage != PAGE_PROFILES) {
                    showProfiles();
                }
                return true;
            }

            if (id == R.id.nav_apps) {
                if (currentPage != PAGE_APPS) {
                    showApps(false);
                }
                return true;
            }

            if (id == R.id.nav_extensions) {
                if (currentPage != PAGE_EXTENSIONS) {
                    showExtensions();
                }
                return true;
            }

            return false;
        });
    }

    private void selectBottomItem(int itemId) {
        BottomNavigationView bottomNavigation = findViewById(R.id.bottomNavigation);

        if (bottomNavigation.getSelectedItemId() != itemId) {
            bottomNavigation.setSelectedItemId(itemId);
        }
    }

    private void setSearchTextSilently(String text) {
        if (searchInput == null) {
            return;
        }

        String value = text == null ? "" : text;

        if (searchInput.getText() != null && value.equals(searchInput.getText().toString())) {
            return;
        }

        suppressSearchTextChange = true;
        searchInput.setText(value);
        suppressSearchTextChange = false;
    }
    private void setupSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (suppressSearchTextChange) {
                    return;
                }

                String keyword = s == null ? "" : s.toString();

                if (currentPage == PAGE_APPS) {
                    appRuleAdapter.filter(keyword);
                } else if (currentPage == PAGE_PROFILES) {
                    profileAdapter.filter(keyword);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void showHome() {
        currentPage = PAGE_HOME;

        headerTitle.setText("FakeContactBook");
        headerSub.setText("https://github.com/FourTwooo/FakeContactBook");

        pageHome.setVisibility(View.VISIBLE);
        pageList.setVisibility(View.GONE);
        pageExtensions.setVisibility(View.GONE);

        fab.setVisibility(View.VISIBLE);
        fab.setText("新建方案");
        fab.setIconResource(android.R.drawable.ic_input_add);
        fab.setOnClickListener(v -> showProfileActionDialog());

        refreshHomeStats();
        refreshGlobalSpinner();
    }

    private void showProfiles() {
        currentPage = PAGE_PROFILES;

        headerTitle.setText("通讯录方案");
        headerSub.setText("每套方案可以独立维护联系人。规则页再决定哪个 App 使用哪套方案。");

        pageHome.setVisibility(View.GONE);
        pageList.setVisibility(View.VISIBLE);
        pageExtensions.setVisibility(View.GONE);

        sectionTitle.setText("方案列表");
        sectionSub.setText("支持新建空方案、VCF 导入。点进方案后可以按姓名、手机号、邮箱、社交资料搜索。");

        searchLayout.setVisibility(View.VISIBLE);
        searchLayout.setHint("搜索方案名或 ID");
        setSearchTextSilently("");

        recyclerView.setAdapter(profileAdapter);
        loadProfilesForProfilesPage();

        fab.setVisibility(View.VISIBLE);
        fab.setText("新建方案");
        fab.setIconResource(android.R.drawable.ic_input_add);
        fab.setOnClickListener(v -> showProfileActionDialog());
    }

    private void loadProfilesForProfilesPage() {
        final int request = ++profilePageRequestSeq;

        executor.execute(() -> {
            ArrayList<ContactProfile> profiles = ConfigStore.getProfiles(this);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (request != profilePageRequestSeq || currentPage != PAGE_PROFILES) {
                    return;
                }

                profileAdapter.setData(profiles);
            });
        });
    }

    private void showApps(boolean forceReload) {
        currentPage = PAGE_APPS;

        headerTitle.setText("应用规则");
        headerSub.setText("指定某个 App 返回哪套通讯录。指定 App 规则优先级高于全局规则。");

        pageHome.setVisibility(View.GONE);
        pageList.setVisibility(View.VISIBLE);
        pageExtensions.setVisibility(View.GONE);

        sectionTitle.setText("目标 App");
        sectionSub.setText("每个 App 可独立开关，并绑定不同通讯录方案。");

        searchLayout.setVisibility(View.VISIBLE);
        searchLayout.setHint("搜索应用名或包名");
        setSearchTextSilently("");

        recyclerView.setAdapter(appRuleAdapter);
        loadAppRuleMetaForAppsPage();

        fab.setVisibility(View.VISIBLE);
        fab.setText("刷新应用");
        fab.setIconResource(android.R.drawable.ic_popup_sync);
        fab.setOnClickListener(v -> loadApps(true));

        if (!appsLoaded || forceReload) {
            loadApps(forceReload);
        }
    }

    private void loadAppRuleMetaForAppsPage() {
        final int request = ++appMetaRequestSeq;

        executor.execute(() -> {
            ArrayList<ContactProfile> profiles = ConfigStore.getProfiles(this);
            HashMap<String, AppRule> rules = ConfigStore.getRules(this);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (request != appMetaRequestSeq || currentPage != PAGE_APPS) {
                    return;
                }

                appRuleAdapter.setMeta(profiles, rules);
            });
        });
    }
    private void showExtensions() {
        currentPage = PAGE_EXTENSIONS;

        headerTitle.setText("扩展能力");
        headerSub.setText("Content Provider API、HTTP API、手机号生成器、通讯录核查");

        pageHome.setVisibility(View.GONE);
        pageList.setVisibility(View.GONE);
        pageExtensions.setVisibility(View.VISIBLE);

        /*
         * 这里不要再显示“新增字段”。
         * 第三方社交字段不由宿主端配置，而是被 Hook App 通过 packageName + phone 提交。
         */
        fab.setVisibility(View.GONE);

        refreshHomeStats();
    }

    private void refreshHomeStats() {
        final int request = ++statsRequestSeq;

        executor.execute(() -> {
            ArrayList<ContactProfile> profiles = ConfigStore.getProfiles(this);

            int contactCount = 0;
            int socialCount = 0;

            for (ContactProfile profile : profiles) {
                if (profile == null || profile.contacts == null) {
                    continue;
                }

                contactCount += profile.contacts.size();

                for (FakeContact contact : profile.contacts) {
                    if (contact != null && contact.socialProfiles != null) {
                        socialCount += contact.socialProfiles.size();
                    }
                }
            }

            int enabledRuleCount = 0;
            HashMap<String, AppRule> rules = ConfigStore.getRules(this);

            for (AppRule rule : rules.values()) {
                if (rule != null && rule.enabled) {
                    enabledRuleCount++;
                }
            }

            int finalProfileCount = profiles.size();
            int finalContactCount = contactCount;
            int finalSocialCount = socialCount;
            int finalEnabledRuleCount = enabledRuleCount;

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (request != statsRequestSeq) {
                    return;
                }

                textProfileStat.setText(String.valueOf(finalProfileCount));
                textContactStat.setText(String.valueOf(finalContactCount));
                textRuleStat.setText(String.valueOf(finalEnabledRuleCount));
                textSocialStat.setText(String.valueOf(finalSocialCount));
            });
        });
    }

    private void refreshGlobalSpinner() {
        final int request = ++globalSpinnerRequestSeq;

        executor.execute(() -> {
            ArrayList<ContactProfile> profiles = ConfigStore.getProfiles(this);
            String globalId = ConfigStore.getGlobalProfileId(this);

            ArrayList<String> names = new ArrayList<>();

            for (ContactProfile profile : profiles) {
                int count = profile.contacts == null ? 0 : profile.contacts.size();
                names.add(profile.name + "（" + count + "）");
            }

            if (names.isEmpty()) {
                names.add("请先新建通讯录方案");
            }

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                if (request != globalSpinnerRequestSeq) {
                    return;
                }

                suppressGlobalSpinner = true;

                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        this,
                        android.R.layout.simple_spinner_item,
                        names
                );
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

                spinnerGlobalProfile.setAdapter(adapter);
                spinnerGlobalProfile.setEnabled(!profiles.isEmpty());

                int index = 0;

                for (int i = 0; i < profiles.size(); i++) {
                    if (profiles.get(i).id.equals(globalId)) {
                        index = i;
                        break;
                    }
                }

                spinnerGlobalProfile.setSelection(index, false);
                suppressGlobalSpinner = false;

                spinnerGlobalProfile.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        if (suppressGlobalSpinner || profiles.isEmpty()) {
                            return;
                        }

                        if (position >= 0 && position < profiles.size()) {
                            ConfigStore.setGlobalProfileId(MainActivity.this, profiles.get(position).id);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });
            });
        });
    }

    private void showProfileActionDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("通讯录方案")
                .setItems(new String[]{"新建空方案", "从 VCF 导入"}, (dialog, which) -> {
                    if (which == 0) {
                        showCreateProfileDialog();
                    } else {
                        pickVcfFile();
                    }
                })
                .show();
    }

    private void showCreateProfileDialog() {
        EditText input = new EditText(this);
        input.setHint("例如：审核专用、空号方案、朋友列表");
        input.setSingleLine(true);

        int padding = dp(18);
        input.setPadding(padding, padding / 2, padding, padding / 2);

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("新建通讯录方案")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = input.getText() == null ? "" : input.getText().toString().trim();

            if (TextUtils.isEmpty(name)) {
                input.setError("请填写方案名称");
                return;
            }

            ContactProfile profile = new ContactProfile(name);
            ConfigStore.upsertProfile(this, profile);

            dialog.dismiss();

            refreshHomeStats();
            refreshGlobalSpinner();
            openEditor(profile.id);
        }));

        dialog.show();
    }

    private void pickVcfFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/*");

        String[] mimeTypes = new String[]{
                "text/x-vcard",
                "text/vcard",
                "text/directory",
                "text/plain",
                "application/octet-stream"
        };

        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
        startActivityForResult(intent, REQ_IMPORT_VCF);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_IMPORT_VCF && resultCode == RESULT_OK && data != null && data.getData() != null) {
            showVcfImportOptionsDialog(data.getData());
        }
    }

    private void showVcfImportOptionsDialog(Uri uri) {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(18);
        container.setPadding(padding, dp(8), padding, 0);

        EditText inputName = new EditText(this);
        inputName.setHint("方案基础名称，例如：手机号表");
        inputName.setSingleLine(true);
        inputName.setInputType(InputType.TYPE_CLASS_TEXT);
        inputName.setText("VCF导入 " + new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(new Date()));

        container.addView(inputName, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        EditText inputLimit = new EditText(this);
        inputLimit.setHint("每个方案最多导入数量，例如：2000");
        inputLimit.setSingleLine(true);
        inputLimit.setInputType(InputType.TYPE_CLASS_NUMBER);
        inputLimit.setText("2000");

        container.addView(inputLimit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView tip = new TextView(this);
        tip.setText("例：VCF 有 5000 个联系人，上限 2000，基础名为“手机号表”，会创建：手机号表、手机号表_1、手机号表_2。填 0 表示不分批。");
        tip.setTextSize(12);
        tip.setPadding(0, dp(8), 0, 0);

        container.addView(tip, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle("VCF 导入设置")
                .setView(container)
                .setNegativeButton("取消", null)
                .setPositiveButton("开始导入", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String baseName = inputName.getText() == null ? "" : inputName.getText().toString().trim();
            String limitText = inputLimit.getText() == null ? "" : inputLimit.getText().toString().trim();

            if (TextUtils.isEmpty(baseName)) {
                inputName.setError("请填写方案基础名称");
                return;
            }

            Integer batchLimit = parseBatchLimit(limitText);
            if (batchLimit == null) {
                inputLimit.setError("请输入 0 或正整数");
                return;
            }

            dialog.dismiss();
            importVcf(uri, baseName, batchLimit);
        }));

        dialog.show();
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

    private void importVcf(Uri uri, String baseName, int batchLimit) {
        executor.execute(() -> {
            try {
                ArrayList<FakeContact> contacts = VcfParser.parse(this, uri);

                if (contacts == null || contacts.isEmpty()) {
                    runOnUiThread(() -> Toast.makeText(this, "VCF 中没有解析到联系人", Toast.LENGTH_LONG).show());
                    return;
                }

                ArrayList<ContactProfile> createdProfiles = buildBatchedProfiles(baseName, contacts, batchLimit);

                for (ContactProfile profile : createdProfiles) {
                    ConfigStore.upsertProfile(this, profile);
                }

                String firstProfileId = createdProfiles.isEmpty() ? "" : createdProfiles.get(0).id;
                int totalCount = contacts.size();
                int profileCount = createdProfiles.size();

                runOnUiThread(() -> {
                    String message = "导入 " + totalCount + " 个联系人，创建 " + profileCount + " 个方案";
                    if (batchLimit > 0) {
                        message += "，每个方案最多 " + batchLimit + " 个";
                    } else {
                        message += "，未分批";
                    }

                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();

                    refreshGlobalSpinner();
                    refreshHomeStats();

                    selectBottomItem(R.id.nav_profiles);
                    showProfiles();

                    if (!TextUtils.isEmpty(firstProfileId)) {
                        openEditor(firstProfileId);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "VCF 导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private ArrayList<ContactProfile> buildBatchedProfiles(String baseName, ArrayList<FakeContact> contacts, int batchLimit) {
        ArrayList<ContactProfile> result = new ArrayList<>();

        if (contacts == null || contacts.isEmpty()) {
            return result;
        }

        int total = contacts.size();
        int realBatchSize = batchLimit <= 0 ? total : batchLimit;

        int batchIndex = 0;

        for (int start = 0; start < total; start += realBatchSize) {
            int end = Math.min(start + realBatchSize, total);

            String profileName = batchIndex == 0
                    ? baseName
                    : baseName + "_" + batchIndex;

            ContactProfile profile = new ContactProfile(profileName);

            for (int i = start; i < end; i++) {
                profile.contacts.add(contacts.get(i));
            }

            result.add(profile);
            batchIndex++;
        }

        return result;
    }

    private void confirmDeleteProfile(ContactProfile profile) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("删除方案")
                .setMessage("确定删除 “" + profile.name + "” 吗？相关 App 规则会自动关闭。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    ConfigStore.deleteProfile(this, profile.id);
                    refreshGlobalSpinner();
                    refreshHomeStats();
                    showProfiles();
                })
                .show();
    }

    private void openEditor(String profileId) {
        Intent intent = new Intent(this, ProfileEditorActivity.class);
        intent.putExtra("profileId", profileId);
        startActivity(intent);
    }

    private void loadApps(boolean forceReload) {
        executor.execute(() -> {
            ArrayList<InstalledApp> apps = new ArrayList<>();
            PackageManager pm = getPackageManager();

            try {
                for (ApplicationInfo info : pm.getInstalledApplications(PackageManager.GET_META_DATA)) {
                    if (getPackageName().equals(info.packageName)) {
                        continue;
                    }

                    String label = String.valueOf(info.loadLabel(pm));
                    apps.add(new InstalledApp(label, info.packageName, info.loadIcon(pm)));
                }
            } catch (Exception ignored) {
            }

            Collections.sort(apps, (a, b) -> a.label.compareToIgnoreCase(b.label));

            ArrayList<ContactProfile> profiles = ConfigStore.getProfiles(this);
            HashMap<String, AppRule> rules = ConfigStore.getRules(this);

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                appsLoaded = true;

                appRuleAdapter.setAll(apps, profiles, rules);

                if (apps.isEmpty()) {
                    Toast.makeText(
                            this,
                            "没有读取到应用列表，请确认 QUERY_ALL_PACKAGES 权限/系统策略",
                            Toast.LENGTH_LONG
                    ).show();
                } else if (forceReload) {
                    Toast.makeText(this, "已刷新应用列表", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void showApiGuideDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("第三方社交平台资料提交 API")
                .setMessage(
                        "提交方式推荐使用 ContentProvider.call。\n\n" +
                                "URI:\n" +
                                "content://com.fourtwo.fakecontactbook.provider/social\n\n" +
                                "method:\n" +
                                "upsertSocialProfile\n\n" +
                                "Bundle 参数:\n" +
                                "payloadJson\n\n" +
                                "payloadJson 必须包含：\n\n" +
                                "{\n" +
                                "  \"packageName\": \"社交平台 App 包名\",\n" +
                                "  \"phone\": \"手机号\"\n" +
                                "}\n\n" +
                                "其余字段由被 Hook App 自由决定，宿主端会原样保存。"
                )
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showGeneratorGuideDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("手机号生成器入口预留")
                .setMessage(
                        "后续可以在这里做两种模式：\n\n" +
                                "1. App 内置生成器：号段、地区、数量、姓名模板、邮箱模板。\n\n" +
                                "2. 外部接入生成器：通过 HTTP API 把结果写入指定通讯录方案。\n\n" +
                                "当前先保留入口，避免所有功能继续挤到通讯录列表页。"
                )
                .setPositiveButton("知道了", null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void refreshXposedProviderStatus() {
        if (textXposedProviderStatusTitle == null || textXposedProviderStatusSub == null) {
            return;
        }

        textXposedProviderStatusTitle.setText("Provider Hook 状态：检测中");
        textXposedProviderStatusSub.setText("正在检测 com.android.providers.contacts 是否已被 LSPosed 挂载。");

        if (btnCheckXposedProviderStatus != null) {
            btnCheckXposedProviderStatus.setEnabled(false);
        }

        executor.execute(() -> {
            ProviderHookStatus status = checkXposedProviderHook();

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                applyXposedProviderStatus(status);

                if (btnCheckXposedProviderStatus != null) {
                    btnCheckXposedProviderStatus.setEnabled(true);
                }
            });
        });
    }

    private ProviderHookStatus checkXposedProviderHook() {
        Cursor cursor = null;

        try {
            Uri uri = ContactsContract.Contacts.CONTENT_URI
                    .buildUpon()
                    .appendQueryParameter(PROVIDER_HOOK_PROBE_PARAM, "1")
                    .appendQueryParameter("_t", String.valueOf(System.currentTimeMillis()))
                    .build();

            cursor = getContentResolver().query(
                    uri,
                    new String[]{"_id"},
                    null,
                    null,
                    null
            );

            if (cursor == null) {
                return ProviderHookStatus.off("未检测到 Provider Hook：Cursor 为 null。");
            }

            int hookedIndex = cursor.getColumnIndex(PROVIDER_HOOK_PROBE_COLUMN);
            int versionIndex = cursor.getColumnIndex(PROVIDER_HOOK_PROBE_VERSION_COLUMN);

            if (hookedIndex < 0) {
                return ProviderHookStatus.off("未检测到 Provider Hook：通讯录 Provider 没有返回探针字段。");
            }

            if (!cursor.moveToFirst()) {
                return ProviderHookStatus.off("未检测到 Provider Hook：探针结果为空。");
            }

            boolean hooked = cursor.getInt(hookedIndex) == 1;
            int version = versionIndex >= 0 ? cursor.getInt(versionIndex) : 0;

            if (hooked) {
                return ProviderHookStatus.on("已检测到 com.android.providers.contacts 被 Hook，版本 " + version + "。");
            }

            return ProviderHookStatus.off("未检测到 Provider Hook：探针值不是 1。");
        } catch (Throwable throwable) {
            return ProviderHookStatus.off(
                    "未检测到 Provider Hook。请确认 LSPosed 作用域已勾选“通讯录存储 / com.android.providers.contacts”，并重启手机或重启 android.process.acore。\n\n"
                            + "错误：" + throwable.getClass().getSimpleName()
            );
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void applyXposedProviderStatus(ProviderHookStatus status) {
        if (status == null) {
            status = ProviderHookStatus.off("检测失败。");
        }

        if (status.enabled) {
            textXposedProviderStatusTitle.setText("Provider Hook 状态：已开启");
            textXposedProviderStatusSub.setText(status.message);
        } else {
            textXposedProviderStatusTitle.setText("Provider Hook 状态：未开启");
            textXposedProviderStatusSub.setText(status.message);
        }
    }

    private static class ProviderHookStatus {
        final boolean enabled;
        final String message;

        private ProviderHookStatus(boolean enabled, String message) {
            this.enabled = enabled;
            this.message = message == null ? "" : message;
        }

        static ProviderHookStatus on(String message) {
            return new ProviderHookStatus(true, message);
        }

        static ProviderHookStatus off(String message) {
            return new ProviderHookStatus(false, message);
        }
    }
}