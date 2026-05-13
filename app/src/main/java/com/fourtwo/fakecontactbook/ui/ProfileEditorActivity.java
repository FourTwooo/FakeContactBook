package com.fourtwo.fakecontactbook.ui;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.model.FakeContact;
import com.fourtwo.fakecontactbook.model.SocialProfileInfo;
import com.fourtwo.fakecontactbook.store.ConfigStore;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ProfileEditorActivity extends AppCompatActivity {
    private ContactProfile profile;
    private Bitmap selectedAvatarSearchThumb = null;
    private TextInputEditText inputProfileName;
    private TextInputEditText inputContactSearch;
    private Spinner spinnerSearchField;
    private TextView textContactCount;
    private ContactAdapter contactAdapter;

    private final ArrayList<String> searchFieldKeys = new ArrayList<>();
    private String selectedSearchField = "all";

    private static final int REQ_PICK_AVATAR_IMAGE = 2601;
    private static final String SEARCH_KEY_SOCIAL_FIELD_PREFIX = "social_field:";
    private static final double MIN_IMAGE_MATCH_SCORE = 0.50d;

    private TextInputLayout searchInputLayout;

    private boolean suppressSearchSpinnerEvent = false;
    private boolean suppressSearchTextEvent = false;

    private boolean imageSearchActive = false;
    private String imageSearchFieldKey = "";
    private final ArrayList<Integer> imageSearchIndexes = new ArrayList<>();
    private final HashMap<Integer, Double> imageSearchScores = new HashMap<>();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_editor);

        String profileId = getIntent().getStringExtra("profileId");
        profile = ConfigStore.getProfile(this, profileId);

        if (profile == null) {
            profile = new ContactProfile("未命名方案");
            ConfigStore.upsertProfile(this, profile);
        }

        bindViews();
        setupToolbar();
        setupProfileCard();
        setupContactList();
        setupSearch();
        applyContactFilter();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSearchSpinnerOptions();
        applyContactFilter();
    }

    private void bindViews() {
        inputProfileName = findViewById(R.id.inputProfileName);
        inputContactSearch = findViewById(R.id.inputContactSearch);
        spinnerSearchField = findViewById(R.id.spinnerSearchField);
        textContactCount = findViewById(R.id.textContactCount);
        searchInputLayout = findViewById(R.id.searchInputLayout);
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }
    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupProfileCard() {
        inputProfileName.setText(profile.name);

        MaterialButton save = findViewById(R.id.btnSaveProfile);
        save.setText("保存");
        save.setOnClickListener(v -> saveProfileName());
    }

    private void setupContactList() {
        RecyclerView recyclerView = findViewById(R.id.recyclerContacts);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        contactAdapter = new ContactAdapter(new ContactAdapter.Listener() {
            @Override
            public void onEdit(FakeContact contact, int sourcePosition) {
                showContactDialog(contact, sourcePosition);
            }

            @Override
            public void onDelete(FakeContact contact, int sourcePosition) {
                confirmDeleteContact(sourcePosition);
            }

            @Override
            public void onDetail(FakeContact contact, int sourcePosition) {
                showSocialDetailDialog(contact);
            }
        });

        recyclerView.setAdapter(contactAdapter);

        ExtendedFloatingActionButton fab = findViewById(R.id.fabAddContact);
        fab.setOnClickListener(v -> showContactDialog(null, -1));
    }

    private void setupSearch() {
        setupSearchSpinner();
        setupSearchImageIcon();

        inputContactSearch.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!suppressSearchTextEvent) {
                    clearImageSearchState();
                }

                applyContactFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
    }

    private void refreshSearchSpinnerOptions() {
        String previousKey = selectedSearchField;

        ArrayList<String> labels = new ArrayList<>();
        ArrayList<String> keys = new ArrayList<>();

        labels.add("全部");
        keys.add("all");

        labels.add("姓名");
        keys.add("name");

        labels.add("手机号");
        keys.add("phone");

        labels.add("邮箱");
        keys.add("email");

        appendDynamicSocialSearchFields(labels, keys);

        int selectedIndex = keys.indexOf(previousKey);
        if (selectedIndex < 0) {
            selectedIndex = 0;
        }

        searchFieldKeys.clear();
        searchFieldKeys.addAll(keys);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        suppressSearchSpinnerEvent = true;
        spinnerSearchField.setAdapter(adapter);
        spinnerSearchField.setSelection(selectedIndex, false);
        selectedSearchField = searchFieldKeys.get(selectedIndex);
        suppressSearchSpinnerEvent = false;

        updateSearchImageIconVisibility();
    }

    private void appendDynamicSocialSearchFields(ArrayList<String> labels, ArrayList<String> keys) {
        if (profile == null || profile.contacts == null) {
            return;
        }

        LinkedHashSet<String> added = new LinkedHashSet<>();

        for (FakeContact contact : profile.contacts) {
            if (contact == null || contact.socialProfiles == null) {
                continue;
            }

            for (SocialProfileInfo info : contact.socialProfiles.values()) {
                if (info == null || TextUtils.isEmpty(info.packageName) || info.payload == null) {
                    continue;
                }

                Iterator<String> iterator = info.payload.keys();

                while (iterator.hasNext()) {
                    String fieldName = iterator.next();

                    if (TextUtils.isEmpty(fieldName)) {
                        continue;
                    }

                    /*
                     * _updatedAt 是宿主端内部字段，没必要作为搜索项。
                     */
                    if ("_updatedAt".equals(fieldName)) {
                        continue;
                    }

                    String label = info.packageName + ":" + fieldName;

                    if (added.add(label)) {
                        labels.add(label);
                        keys.add(SEARCH_KEY_SOCIAL_FIELD_PREFIX + label);
                    }
                }
            }
        }
    }
    private void setupSearchSpinner() {
        spinnerSearchField.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (suppressSearchSpinnerEvent) {
                    return;
                }

                if (position >= 0 && position < searchFieldKeys.size()) {
                    String oldKey = selectedSearchField;
                    selectedSearchField = searchFieldKeys.get(position);

                    if (!selectedSearchField.equals(oldKey)) {
                        boolean wasImageMode = imageSearchActive || isAvatarSearchFieldKey(oldKey);

                        clearImageSearchState();

                        /*
                         * 只有从头像识别模式切出去时，清空搜索框。
                         * 普通“姓名 / 手机号 / 邮箱”之间切换，不强制清空用户输入。
                         */
                        if (wasImageMode) {
                            setSearchTextSilently("");
                        }
                    }

                    updateSearchImageIconVisibility();
                    applyContactFilter();
                }
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        refreshSearchSpinnerOptions();
    }


    private boolean isAvatarSearchFieldKey(String rawKey) {
        SocialFieldKey key = parseSocialFieldKey(rawKey);
        return key != null && isAvatarFieldName(key.fieldName);
    }

    private boolean contactMatchesSearch(FakeContact contact, String fieldKey, String keyword) {
        if (contact == null) {
            return false;
        }

        if (TextUtils.isEmpty(keyword)) {
            return true;
        }

        if (fieldKey != null && fieldKey.startsWith(SEARCH_KEY_SOCIAL_FIELD_PREFIX)) {
            SocialFieldKey socialFieldKey = parseSocialFieldKey(fieldKey);
            return socialFieldKey != null && contactMatchesSocialField(contact, socialFieldKey, keyword);
        }

        return contact.matches(this, fieldKey, keyword);
    }

    private boolean contactMatchesSocialField(FakeContact contact, SocialFieldKey fieldKey, String keyword) {
        if (contact.socialProfiles == null || fieldKey == null || TextUtils.isEmpty(keyword)) {
            return false;
        }

        SocialProfileInfo info = contact.socialProfiles.get(fieldKey.packageName);
        if (info == null || info.payload == null) {
            return false;
        }

        Object value = getPayloadValueIgnoreCase(info.payload, fieldKey.fieldName);
        if (value == null || value == JSONObject.NULL) {
            return false;
        }

        String text = String.valueOf(value);
        return text.toLowerCase(Locale.US).contains(keyword.trim().toLowerCase(Locale.US));
    }

    private Object getPayloadValueIgnoreCase(JSONObject payload, String wantedKey) {
        if (payload == null || TextUtils.isEmpty(wantedKey)) {
            return null;
        }

        Object direct = payload.opt(wantedKey);
        if (direct != null && direct != JSONObject.NULL) {
            return direct;
        }

        Iterator<String> iterator = payload.keys();

        while (iterator.hasNext()) {
            String key = iterator.next();

            if (wantedKey.equalsIgnoreCase(key)) {
                Object value = payload.opt(key);
                return value == JSONObject.NULL ? null : value;
            }
        }

        return null;
    }

    private SocialFieldKey parseSocialFieldKey(String rawKey) {
        if (TextUtils.isEmpty(rawKey)) {
            return null;
        }

        if (!rawKey.startsWith(SEARCH_KEY_SOCIAL_FIELD_PREFIX)) {
            return null;
        }

        String value = rawKey.substring(SEARCH_KEY_SOCIAL_FIELD_PREFIX.length());
        int colon = value.indexOf(':');

        if (colon <= 0 || colon >= value.length() - 1) {
            return null;
        }

        SocialFieldKey key = new SocialFieldKey();
        key.packageName = value.substring(0, colon);
        key.fieldName = value.substring(colon + 1);
        return key;
    }

    private void setupSearchImageIcon() {
        if (searchInputLayout == null) {
            return;
        }

        /*
         * 不再使用 startIcon 显示图片，避免搜索框变臃肿。
         */
        searchInputLayout.setStartIconDrawable((Drawable) null);

        searchInputLayout.setEndIconMode(TextInputLayout.END_ICON_CUSTOM);
        searchInputLayout.setEndIconOnClickListener(v -> pickAvatarSearchImage());

        setSearchEndIconCamera();
        updateSearchImageIconVisibility();
    }

    private void updateSearchImageIconVisibility() {
        if (searchInputLayout == null) {
            return;
        }

        boolean showIcon = isSelectedAvatarSearchField();
        searchInputLayout.setEndIconVisible(showIcon);

        if (!showIcon) {
            return;
        }

        if (imageSearchActive
                && selectedAvatarSearchThumb != null
                && selectedSearchField.equals(imageSearchFieldKey)) {
            setSearchEndIconBitmap(selectedAvatarSearchThumb);
        } else {
            setSearchEndIconCamera();
        }
    }
    private boolean isSelectedAvatarSearchField() {
        return isAvatarSearchFieldKey(selectedSearchField);
    }
    private void setSearchEndIconCamera() {
        if (searchInputLayout == null) {
            return;
        }

        /*
         * 清掉 tint，避免后面设置图片时被 Material 默认颜色染成灰色。
         */
        searchInputLayout.setEndIconTintList(null);
        searchInputLayout.setEndIconDrawable(android.R.drawable.ic_menu_camera);
        searchInputLayout.setEndIconContentDescription("选择图片识别");
    }

    private void setSearchEndIconBitmap(Bitmap bitmap) {
        if (searchInputLayout == null || bitmap == null) {
            return;
        }

        int size = dp(28);

        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);
        drawable.setBounds(0, 0, size, size);

        /*
         * 关键：图片 icon 不能被 tint，不然就会变成你截图里的灰块。
         */
        searchInputLayout.setEndIconTintList(null);
        searchInputLayout.setEndIconDrawable(drawable);
        searchInputLayout.setEndIconContentDescription("重新选择图片识别");
        searchInputLayout.setEndIconVisible(true);
    }
    private boolean isAvatarFieldName(String fieldName) {
        if (TextUtils.isEmpty(fieldName)) {
            return false;
        }

        String lower = fieldName.toLowerCase(Locale.US);

        return lower.contains("avatar")
                || lower.contains("head")
                || lower.contains("photo")
                || lower.contains("icon")
                || fieldName.contains("头像");
    }

    private void pickAvatarSearchImage() {
        if (!isSelectedAvatarSearchField()) {
            Toast.makeText(this, "请选择头像字段后再进行图片识别", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");

        startActivityForResult(intent, REQ_PICK_AVATAR_IMAGE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_PICK_AVATAR_IMAGE
                && resultCode == RESULT_OK
                && data != null
                && data.getData() != null) {
            runAvatarImageSearch(data.getData());
        }
    }
    private void runAvatarImageSearch(Uri selectedImageUri) {
        SocialFieldKey fieldKey = parseSocialFieldKey(selectedSearchField);

        if (fieldKey == null || !isAvatarFieldName(fieldKey.fieldName)) {
            Toast.makeText(this, "请选择头像字段后再进行图片识别", Toast.LENGTH_SHORT).show();
            return;
        }

        if (profile == null || profile.contacts == null || profile.contacts.isEmpty()) {
            Toast.makeText(this, "当前方案没有联系人", Toast.LENGTH_SHORT).show();
            return;
        }

        String currentFieldKey = selectedSearchField;
        ArrayList<FakeContact> snapshot = new ArrayList<>(profile.contacts);

        Toast.makeText(this, "正在识别头像，请稍候...", Toast.LENGTH_SHORT).show();

        executor.execute(() -> {
            try {
                Bitmap target = decodeBitmapFromUri(selectedImageUri, 512);
                if (target == null) {
                    throw new IllegalStateException("无法读取选择的图片");
                }
                Bitmap thumb = createSquareThumbnail(target, dp(28));

                runOnUiThread(() -> {
                    if (currentFieldKey.equals(selectedSearchField) && thumb != null) {
                        selectedAvatarSearchThumb = thumb;
                        setSearchEndIconBitmap(thumb);
                        setSearchTextSilently("");
                    }
                });


                ArrayList<ImageMatch> matches = new ArrayList<>();

                for (int i = 0; i < snapshot.size(); i++) {
                    FakeContact contact = snapshot.get(i);
                    Bitmap candidate = decodeContactAvatarBitmap(contact, fieldKey);

                    if (candidate == null) {
                        continue;
                    }

                    double score = scoreImages(target, candidate);

                    if (score >= MIN_IMAGE_MATCH_SCORE) {
                        ImageMatch match = new ImageMatch();
                        match.index = i;
                        match.score = score;
                        matches.add(match);
                    }
                }

                Collections.sort(matches, (a, b) -> Double.compare(b.score, a.score));

                runOnUiThread(() -> {
                    if (!currentFieldKey.equals(selectedSearchField)) {
                        return;
                    }

                    imageSearchActive = true;
                    imageSearchFieldKey = currentFieldKey;
                    imageSearchIndexes.clear();
                    imageSearchScores.clear();

                    for (ImageMatch match : matches) {
                        imageSearchIndexes.add(match.index);
                        imageSearchScores.put(match.index, match.score);
                    }

                    selectedAvatarSearchThumb = thumb;
                    if (selectedAvatarSearchThumb != null) {
                        setSearchEndIconBitmap(selectedAvatarSearchThumb);
                    }

                    /*
                     * 不再显示“已选择图片”文字。
                     * 搜索框保持空，右侧 icon 本身就是已选择图片。
                     */
                    setSearchTextSilently("");

                    applyContactFilter();

                    if (matches.isEmpty()) {
                        Toast.makeText(this, "没有找到相似头像", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "已按头像相似度排序", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "图片识别失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }
    private Bitmap decodeContactAvatarBitmap(FakeContact contact, SocialFieldKey fieldKey) {
        if (contact == null || fieldKey == null || contact.socialProfiles == null) {
            return null;
        }

        SocialProfileInfo info = contact.socialProfiles.get(fieldKey.packageName);
        if (info == null || info.payload == null) {
            return null;
        }

        Object value = getPayloadValueIgnoreCase(info.payload, fieldKey.fieldName);
        String text = value == null || value == JSONObject.NULL ? "" : String.valueOf(value).trim();

        if (!TextUtils.isEmpty(text) && !text.startsWith("http://") && !text.startsWith("https://")) {
            Bitmap decoded = decodeBase64Bitmap(text);
            if (decoded != null) {
                return decoded;
            }
        }

        /*
         * 字段名是“头像”这类泛称时，尝试走 SocialProfileInfo 内置头像解析。
         */
        return info.decodeAvatarBitmap();
    }

    private Bitmap decodeBitmapFromUri(Uri uri, int maxSize) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }

            BitmapFactory.decodeStream(input, null, bounds);
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = calculateInSampleSize(bounds, maxSize, maxSize);
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;

        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) {
                return null;
            }

            return BitmapFactory.decodeStream(input, null, options);
        }
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        int height = options.outHeight;
        int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            int halfHeight = height / 2;
            int halfWidth = width / 2;

            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return Math.max(1, inSampleSize);
    }

    private Bitmap decodeBase64Bitmap(String raw) {
        if (TextUtils.isEmpty(raw)) {
            return null;
        }

        try {
            String value = raw.trim();
            int comma = value.indexOf(',');

            if (value.startsWith("data:image") && comma >= 0) {
                value = value.substring(comma + 1);
            }

            byte[] bytes = Base64.decode(value, Base64.DEFAULT);
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception ignored) {
            return null;
        }
    }

    private double scoreImages(Bitmap a, Bitmap b) {
        long hashA = dHash(a);
        long hashB = dHash(b);

        int distance = Long.bitCount(hashA ^ hashB);
        double hashScore = 1.0d - Math.min(64, distance) / 64.0d;

        double histScore = colorHistogramIntersection(a, b);

        return hashScore * 0.72d + histScore * 0.28d;
    }

    private long dHash(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, 9, 8, true);
        long hash = 0L;
        int bit = 0;

        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int left = gray(small.getPixel(x, y));
                int right = gray(small.getPixel(x + 1, y));

                if (left > right) {
                    hash |= (1L << bit);
                }

                bit++;
            }
        }

        if (small != source) {
            small.recycle();
        }

        return hash;
    }

    private int gray(int color) {
        return (int) (
                Color.red(color) * 0.299d
                        + Color.green(color) * 0.587d
                        + Color.blue(color) * 0.114d
        );
    }

    private double colorHistogramIntersection(Bitmap a, Bitmap b) {
        double[] ha = colorHistogram(a);
        double[] hb = colorHistogram(b);

        double score = 0.0d;

        for (int i = 0; i < ha.length; i++) {
            score += Math.min(ha[i], hb[i]);
        }

        return score;
    }

    private double[] colorHistogram(Bitmap source) {
        Bitmap small = Bitmap.createScaledBitmap(source, 64, 64, true);
        double[] hist = new double[64];

        int total = 0;

        for (int y = 0; y < small.getHeight(); y++) {
            for (int x = 0; x < small.getWidth(); x++) {
                int color = small.getPixel(x, y);

                int r = Color.red(color) / 64;
                int g = Color.green(color) / 64;
                int b = Color.blue(color) / 64;

                int bin = r * 16 + g * 4 + b;
                hist[bin] += 1.0d;
                total++;
            }
        }

        if (small != source) {
            small.recycle();
        }

        if (total > 0) {
            for (int i = 0; i < hist.length; i++) {
                hist[i] = hist[i] / total;
            }
        }

        return hist;
    }

    private void clearImageSearchState() {
        imageSearchActive = false;
        imageSearchFieldKey = "";
        imageSearchIndexes.clear();
        imageSearchScores.clear();
        selectedAvatarSearchThumb = null;

        if (searchInputLayout != null) {
            searchInputLayout.setStartIconDrawable((Drawable) null);
            setSearchEndIconCamera();
            updateSearchImageIconVisibility();
        }
    }

    private void setSearchTextSilently(String text) {
        if (inputContactSearch == null) {
            return;
        }

        suppressSearchTextEvent = true;

        inputContactSearch.setText(text == null ? "" : text);

        if (inputContactSearch.getText() != null) {
            inputContactSearch.setSelection(inputContactSearch.getText().length());
        }

        suppressSearchTextEvent = false;
    }

    private Bitmap createSquareThumbnail(Bitmap source, int sizePx) {
        if (source == null) {
            return null;
        }

        int width = source.getWidth();
        int height = source.getHeight();

        if (width <= 0 || height <= 0) {
            return null;
        }

        int side = Math.min(width, height);
        int left = (width - side) / 2;
        int top = (height - side) / 2;

        Bitmap cropped = Bitmap.createBitmap(source, left, top, side, side);

        Bitmap thumb = Bitmap.createScaledBitmap(
                cropped,
                Math.max(1, sizePx),
                Math.max(1, sizePx),
                true
        );

        if (cropped != source) {
            cropped.recycle();
        }

        return thumb;
    }

    private static class ImageMatch {
        int index;
        double score;
    }
    private static class SocialFieldKey {
        String packageName;
        String fieldName;
    }

    private String buildImageSearchSummaryText() {
        if (imageSearchIndexes.isEmpty()) {
            return "";
        }

        double max = 0.0d;
        double min = 1.0d;
        double sum = 0.0d;
        int count = 0;

        for (Integer index : imageSearchIndexes) {
            if (index == null) {
                continue;
            }

            Double score = imageSearchScores.get(index);
            if (score == null) {
                continue;
            }

            max = Math.max(max, score);
            min = Math.min(min, score);
            sum += score;
            count++;
        }

        if (count <= 0) {
            return "";
        }

        double avg = sum / count;

        return "，最高 "
                + Math.round(max * 100)
                + "%，最低 "
                + Math.round(min * 100)
                + "%，平均 "
                + Math.round(avg * 100)
                + "%";
    }
    private void applyContactFilter() {
        if (contactAdapter == null || profile == null || profile.contacts == null) {
            return;
        }

        if (imageSearchActive && selectedSearchField.equals(imageSearchFieldKey)) {
            contactAdapter.setFilteredData(profile.contacts, imageSearchIndexes, imageSearchScores);

            textContactCount.setText(
                    "图片识别 "
                            + imageSearchIndexes.size()
                            + " / "
                            + profile.contacts.size()
                            + " 个联系人"
                            + buildImageSearchSummaryText()
            );
            return;
        }

        String keyword = inputContactSearch == null || inputContactSearch.getText() == null
                ? ""
                : inputContactSearch.getText().toString();

        ArrayList<Integer> matched = new ArrayList<>();

        for (int i = 0; i < profile.contacts.size(); i++) {
            FakeContact contact = profile.contacts.get(i);

            if (contact != null && contactMatchesSearch(contact, selectedSearchField, keyword)) {
                matched.add(i);
            }
        }

        contactAdapter.setFilteredData(profile.contacts, matched);
        textContactCount.setText("显示 " + matched.size() + " / " + profile.contacts.size() + " 个联系人");
    }

    private void saveProfileName() {
        String name = inputProfileName.getText() == null ? "" : inputProfileName.getText().toString().trim();

        if (TextUtils.isEmpty(name)) {
            inputProfileName.setError("方案名称不能为空");
            return;
        }

        profile.name = name;
        ConfigStore.upsertProfile(this, profile);

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
    }

    private void showContactDialog(FakeContact oldContact, int position) {
        ScrollView scrollView = new ScrollView(this);

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);

        int padding = dp(20);
        container.setPadding(padding, 8, padding, 0);

        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        EditText name = new EditText(this);
        name.setHint("姓名，例如 张三");
        name.setSingleLine(true);
        name.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        container.addView(name, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        EditText phone = new EditText(this);
        phone.setHint("手机号，例如 13800138000");
        phone.setSingleLine(true);
        phone.setInputType(InputType.TYPE_CLASS_PHONE);
        container.addView(phone, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        EditText email = new EditText(this);
        email.setHint("邮箱，可选");
        email.setSingleLine(true);
        email.setInputType(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        container.addView(email, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (oldContact != null) {
            name.setText(oldContact.name);
            phone.setText(oldContact.phone);
            email.setText(oldContact.email);
        }

        TextView note = new TextView(this);
        note.setText("第三方社交平台字段不在这里手动编辑。它们由被 Hook App 通过 packageName + phone 提交，宿主端按手机号自动归档。");
        note.setTextSize(12);
        note.setPadding(0, dp(10), 0, 0);
        container.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(oldContact == null ? "添加联系人" : "编辑联系人")
                .setView(scrollView)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();

        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String nameValue = name.getText() == null ? "" : name.getText().toString().trim();
            String phoneValue = phone.getText() == null ? "" : phone.getText().toString().trim();
            String emailValue = email.getText() == null ? "" : email.getText().toString().trim();

            if (TextUtils.isEmpty(phoneValue)) {
                phone.setError("请填写手机号");
                return;
            }

            FakeContact contact = oldContact == null ? new FakeContact() : oldContact;
            contact.name = nameValue;
            contact.phone = phoneValue;
            contact.email = emailValue;

            if (position < 0) {
                profile.contacts.add(contact);
            } else if (position < profile.contacts.size()) {
                profile.contacts.set(position, contact);
            }

            ConfigStore.upsertProfile(this, profile);
            applyContactFilter();

            dialog.dismiss();
        }));

        dialog.show();
    }

    private void confirmDeleteContact(int sourcePosition) {
        if (sourcePosition < 0 || sourcePosition >= profile.contacts.size()) return;

        FakeContact contact = profile.contacts.get(sourcePosition);

        new MaterialAlertDialogBuilder(this)
                .setTitle("删除联系人")
                .setMessage("确定删除 “" + contact.name + "” 吗？")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (dialog, which) -> {
                    profile.contacts.remove(sourcePosition);
                    ConfigStore.upsertProfile(this, profile);
                    applyContactFilter();
                })
                .show();
    }

    private void showSocialDetailDialog(FakeContact contact) {
        ArrayList<SocialProfileInfo> socialList = contact.getSortedSocialProfiles();

        if (socialList.isEmpty()) {
            Toast.makeText(this, "这个联系人没有第三方社交平台资料", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));

        scrollView.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        for (SocialProfileInfo info : socialList) {
            root.addView(createSocialDetailBlock(this, info));
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(contact.name + " 的社交平台资料")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .show();
    }

    private LinearLayout createSocialDetailBlock(Context context, SocialProfileInfo info) {
        LinearLayout block = new LinearLayout(context);
        block.setOrientation(LinearLayout.VERTICAL);
        block.setPadding(0, 0, 0, dp(18));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        ImageView appIcon = new ImageView(context);
        try {
            PackageManager pm = context.getPackageManager();
            Drawable icon = pm.getApplicationIcon(info.packageName);
            appIcon.setImageDrawable(icon);
        } catch (Exception ignored) {
            appIcon.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        header.addView(appIcon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout titleBox = new LinearLayout(context);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        titleBox.setPadding(dp(10), 0, 0, 0);

        TextView title = new TextView(context);
        title.setText(info.getAppLabel(context));
        title.setTextSize(16);
        title.setSingleLine(true);

        TextView sub = new TextView(context);
        sub.setText(info.packageName);
        sub.setTextSize(12);
        sub.setSingleLine(true);

        titleBox.addView(title);
        titleBox.addView(sub);

        header.addView(titleBox, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        block.addView(header);

        Bitmap avatar = info.decodeAvatarBitmap();
        if (avatar != null) {
            ImageView avatarView = new ImageView(context);
            avatarView.setImageBitmap(avatar);
            avatarView.setAdjustViewBounds(true);
            avatarView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            avatarView.setMaxHeight(dp(160));
            avatarView.setPadding(0, dp(10), 0, dp(6));
            avatarView.setContentDescription("点击查看头像，长按保存头像");

            avatarView.setOnClickListener(v -> showAvatarPreview(avatar, info));
            avatarView.setOnLongClickListener(v -> {
                saveAvatarBitmap(avatar, info);
                return true;
            });

            block.addView(avatarView, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            ));
        }

        String avatarUrl = info.getAvatarUrl();
        if (!TextUtils.isEmpty(avatarUrl)) {
            TextView avatarUrlView = new TextView(context);
            avatarUrlView.setText("头像地址：" + avatarUrl);
            avatarUrlView.setTextSize(12);
            avatarUrlView.setPadding(0, dp(8), 0, 0);
            block.addView(avatarUrlView);
        }

        TextView updated = new TextView(context);
        updated.setText("更新时间：" + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(info.updatedAt)));
        updated.setTextSize(12);
        updated.setPadding(0, dp(8), 0, dp(6));
        block.addView(updated);

        JSONObject payload = info.payload;
        if (payload != null) {
            Iterator<String> iterator = payload.keys();

            while (iterator.hasNext()) {
                String key = iterator.next();
                String value = payload.optString(key, "");

                if (shouldHideSocialPayloadKey(key, value)) {
                    continue;
                }

                if (TextUtils.isEmpty(value)) {
                    continue;
                }

                TextView row = new TextView(context);
                row.setText(key + "：\n" + value);
                row.setTextSize(13);
                row.setPadding(0, dp(5), 0, dp(5));
                block.addView(row);
            }
        }

        return block;
    }
    private boolean shouldHideSocialPayloadKey(String key, String value) {
        if (TextUtils.isEmpty(key)) {
            return true;
        }

        String lower = key.toLowerCase(Locale.US);
        String safeValue = value == null ? "" : value.trim();

        boolean avatarName = lower.contains("avatar")
                || lower.contains("head")
                || lower.contains("photo")
                || lower.contains("icon")
                || key.contains("头像");

        if (!avatarName) {
            return false;
        }

        /*
         * avatarBase64、头像Base64、head_base64、photoBase64 等原始图片数据不展示。
         */
        if (lower.contains("base64")) {
            return true;
        }

        /*
         * key 叫头像，并且 value 是 data:image 或明显很长的 base64，也不展示。
         */
        if (safeValue.startsWith("data:image")) {
            return true;
        }

        if (!safeValue.startsWith("http://")
                && !safeValue.startsWith("https://")
                && safeValue.length() > 200) {
            return true;
        }

        /*
         * avatarUrl 这种字段已经单独展示为“头像地址”，payload 里不重复显示。
         */
        return lower.contains("url") || key.contains("地址");
    }

    private void showAvatarPreview(Bitmap avatar, SocialProfileInfo info) {
        if (avatar == null) {
            Toast.makeText(this, "头像为空", Toast.LENGTH_SHORT).show();
            return;
        }

        ScrollView scrollView = new ScrollView(this);

        ImageView imageView = new ImageView(this);
        imageView.setImageBitmap(avatar);
        imageView.setAdjustViewBounds(true);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setPadding(dp(12), dp(12), dp(12), dp(12));
        imageView.setContentDescription("长按保存头像");

        imageView.setOnLongClickListener(v -> {
            saveAvatarBitmap(avatar, info);
            return true;
        });

        scrollView.addView(imageView, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
        ));

        new MaterialAlertDialogBuilder(this)
                .setTitle(info.getPreviewTitle(this) + " 的头像")
                .setView(scrollView)
                .setPositiveButton("关闭", null)
                .show();
    }

    private void saveAvatarBitmap(Bitmap bitmap, SocialProfileInfo info) {
        if (bitmap == null) {
            Toast.makeText(this, "头像为空，无法保存", Toast.LENGTH_SHORT).show();
            return;
        }

        executor.execute(() -> {
            try {
                String fileName = buildAvatarFileName(info);
                Uri saved = saveBitmapToPictures(bitmap, fileName);

                runOnUiThread(() -> Toast.makeText(
                        this,
                        saved == null ? "保存失败" : "头像已保存到相册",
                        Toast.LENGTH_LONG
                ).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(
                        this,
                        "保存头像失败：" + e.getMessage(),
                        Toast.LENGTH_LONG
                ).show());
            }
        });
    }

    private String buildAvatarFileName(SocialProfileInfo info) {
        String pkg = info == null || TextUtils.isEmpty(info.packageName)
                ? "unknown"
                : info.packageName.replaceAll("[^a-zA-Z0-9._-]+", "_");

        return "fakecontactbook_avatar_"
                + pkg
                + "_"
                + System.currentTimeMillis()
                + ".png";
    }

    private Uri saveBitmapToPictures(Bitmap bitmap, String fileName) throws Exception {
        ContentResolver resolver = getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FakeContactBook");
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
        }

        Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri == null) {
            throw new IllegalStateException("无法创建图片文件");
        }

        boolean success = false;

        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                throw new IllegalStateException("无法打开图片输出流");
            }

            success = bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } catch (Exception e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        if (!success) {
            resolver.delete(uri, null, null);
            throw new IllegalStateException("图片压缩失败");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
        }

        return uri;
    }
    private void showSubmitGuideDialog() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("第三方平台资料提交规则")
                .setMessage(
                        "第三方社交平台字段不由宿主端预设。\n\n" +
                                "被 Hook App 提交 JSON，必须包含：\n\n" +
                                "{\n" +
                                "  \"packageName\": \"社交平台 App 包名\",\n" +
                                "  \"phone\": \"手机号\"\n" +
                                "}\n\n" +
                                "其余字段完全自由，例如：\n\n" +
                                "{\n" +
                                "  \"packageName\": \"com.tencent.mobileqq\",\n" +
                                "  \"phone\": \"13800138000\",\n" +
                                "  \"nickname\": \"张三的QQ\",\n" +
                                "  \"account\": \"10001\",\n" +
                                "  \"avatarUrl\": \"https://...\",\n" +
                                "  \"gender\": \"男\"\n" +
                                "}\n\n" +
                                "宿主端会按手机号找到联系人，再按 packageName 更新对应平台资料。"
                )
                .setPositiveButton("知道了", null)
                .show();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}