package com.fourtwo.fakecontactbook.ui;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.model.AppRule;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.fourtwo.fakecontactbook.model.InstalledApp;
import com.fourtwo.fakecontactbook.store.ConfigStore;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;

public class AppRuleAdapter extends RecyclerView.Adapter<AppRuleAdapter.ViewHolder> {
    private final Context context;
    private final ArrayList<InstalledApp> allApps = new ArrayList<>();
    private final ArrayList<InstalledApp> shownApps = new ArrayList<>();
    private final ArrayList<ContactProfile> profiles = new ArrayList<>();
    private String keyword = "";

    private final HashMap<String, AppRule> ruleCache = new HashMap<>();
    private final ArrayList<String> profileNames = new ArrayList<>();
    public AppRuleAdapter(Context context) {
        this.context = context.getApplicationContext();
    }

    public void setProfiles(ArrayList<ContactProfile> data) {
        profiles.clear();

        if (data != null) {
            profiles.addAll(data);
        }

        rebuildProfileNames();
        notifyDataSetChanged();
    }

    public void setMeta(ArrayList<ContactProfile> profileData, HashMap<String, AppRule> rules) {
        profiles.clear();
        if (profileData != null) {
            profiles.addAll(profileData);
        }

        ruleCache.clear();
        if (rules != null) {
            ruleCache.putAll(rules);
        }

        rebuildProfileNames();
        filter(keyword);
    }

    public void setApps(ArrayList<InstalledApp> data) {
        allApps.clear();

        if (data != null) {
            allApps.addAll(data);
        }

        filter(keyword);
    }

    public void setAll(
            ArrayList<InstalledApp> apps,
            ArrayList<ContactProfile> profileData,
            HashMap<String, AppRule> rules
    ) {
        allApps.clear();
        if (apps != null) {
            allApps.addAll(apps);
        }

        profiles.clear();
        if (profileData != null) {
            profiles.addAll(profileData);
        }

        ruleCache.clear();
        if (rules != null) {
            ruleCache.putAll(rules);
        }

        rebuildProfileNames();
        filter(keyword);
    }

    private void rebuildProfileNames() {
        profileNames.clear();

        for (ContactProfile profile : profiles) {
            profileNames.add(profile.name + "（" + (profile.contacts == null ? 0 : profile.contacts.size()) + "）");
        }

        if (profileNames.isEmpty()) {
            profileNames.add("请先新建通讯录方案");
        }
    }
    public void filter(String keyword) {
        this.keyword = keyword == null ? "" : keyword.trim().toLowerCase(Locale.US);

        shownApps.clear();

        if (this.keyword.isEmpty()) {
            shownApps.addAll(allApps);
        } else {
            for (InstalledApp app : allApps) {
                String label = app.label == null ? "" : app.label.toLowerCase(Locale.US);
                String pkg = app.packageName == null ? "" : app.packageName.toLowerCase(Locale.US);

                if (label.contains(this.keyword) || pkg.contains(this.keyword)) {
                    shownApps.add(app);
                }
            }
        }

        sortShownApps();
        notifyDataSetChanged();
    }

    private void sortShownApps() {
        Collections.sort(shownApps, (a, b) -> {
            boolean aEnabled = isRuleEnabled(a.packageName);
            boolean bEnabled = isRuleEnabled(b.packageName);

            if (aEnabled != bEnabled) {
                return aEnabled ? -1 : 1;
            }

            String aLabel = a.label == null ? "" : a.label;
            String bLabel = b.label == null ? "" : b.label;

            int labelCompare = aLabel.compareToIgnoreCase(bLabel);
            if (labelCompare != 0) {
                return labelCompare;
            }

            String aPkg = a.packageName == null ? "" : a.packageName;
            String bPkg = b.packageName == null ? "" : b.packageName;

            return aPkg.compareToIgnoreCase(bPkg);
        });
    }

    private boolean isRuleEnabled(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }

        AppRule rule = ruleCache.get(packageName);
        return rule != null && rule.enabled;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private AppRule getCachedRule(String packageName) {
        AppRule rule = TextUtils.isEmpty(packageName) ? null : ruleCache.get(packageName);

        if (rule == null) {
            String defaultProfileId = profiles.isEmpty() ? "" : profiles.get(0).id;
            return new AppRule(packageName, false, defaultProfileId);
        }

        if (TextUtils.isEmpty(rule.profileId) && !profiles.isEmpty()) {
            return new AppRule(rule.packageName, rule.enabled, profiles.get(0).id);
        }

        return new AppRule(rule.packageName, rule.enabled, rule.profileId);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_rule, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        InstalledApp app = shownApps.get(position);
        holder.icon.setImageDrawable(app.icon);
        holder.name.setText(app.label);
        holder.pkg.setText(app.packageName);

        AppRule rule = getCachedRule(app.packageName);

        ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                holder.spinner.getContext(),
                android.R.layout.simple_spinner_item,
                profileNames
        );
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        holder.spinner.setAdapter(spinnerAdapter);
        holder.spinner.setEnabled(!profiles.isEmpty());

        int selection = findProfileIndex(rule.profileId);
        holder.spinner.setOnItemSelectedListener(null);
        holder.spinner.setSelection(Math.max(0, selection), false);
        holder.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int selected, long id) {
                if (profiles.isEmpty()) return;
                AppRule current = getCachedRule(app.packageName);
                current.packageName = app.packageName;
                current.profileId = profiles.get(selected).id;

                ruleCache.put(app.packageName, current);
                ConfigStore.setRule(context, current);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        holder.switchApp.setOnCheckedChangeListener(null);
        holder.switchApp.setChecked(rule.enabled);
        holder.switchApp.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AppRule current = getCachedRule(app.packageName);
            current.packageName = app.packageName;
            current.enabled = isChecked;

            if (TextUtils.isEmpty(current.profileId) && !profiles.isEmpty()) {
                current.profileId = profiles.get(0).id;
            }

            ruleCache.put(app.packageName, current);
            ConfigStore.setRule(context, current);

            holder.itemView.post(() -> filter(keyword));

            /*
             * 开启后立刻移动到前面；
             * 关闭后立刻回到未开启分组。
             * 用 post 避免在 RecyclerView 正在处理当前点击事件时直接刷新。
             */
            holder.itemView.post(() -> filter(keyword));
        });
    }

    private int findProfileIndex(String profileId) {
        if (TextUtils.isEmpty(profileId)) return 0;
        for (int i = 0; i < profiles.size(); i++) {
            if (profileId.equals(profiles.get(i).id)) return i;
        }
        return 0;
    }

    @Override
    public int getItemCount() {
        return shownApps.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name;
        TextView pkg;
        SwitchMaterial switchApp;
        Spinner spinner;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.iconApp);
            name = itemView.findViewById(R.id.textAppName);
            pkg = itemView.findViewById(R.id.textAppPackage);
            switchApp = itemView.findViewById(R.id.switchApp);
            spinner = itemView.findViewById(R.id.spinnerProfile);
        }
    }
}
