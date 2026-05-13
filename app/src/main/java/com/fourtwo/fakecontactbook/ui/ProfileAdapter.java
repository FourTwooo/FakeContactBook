package com.fourtwo.fakecontactbook.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.model.ContactProfile;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Locale;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {
    public interface Listener {
        void onEdit(ContactProfile profile);
        void onDelete(ContactProfile profile);
    }

    private final ArrayList<ContactProfile> allProfiles = new ArrayList<>();
    private final ArrayList<ContactProfile> shownProfiles = new ArrayList<>();
    private final Listener listener;
    private String keyword = "";

    public ProfileAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setData(ArrayList<ContactProfile> data) {
        allProfiles.clear();
        if (data != null) allProfiles.addAll(data);
        filter(keyword);
    }

    public void filter(String value) {
        keyword = value == null ? "" : value.trim().toLowerCase(Locale.US);
        shownProfiles.clear();

        if (TextUtils.isEmpty(keyword)) {
            shownProfiles.addAll(allProfiles);
        } else {
            for (ContactProfile profile : allProfiles) {
                String name = profile.name == null ? "" : profile.name.toLowerCase(Locale.US);
                String id = profile.id == null ? "" : profile.id.toLowerCase(Locale.US);

                if (name.contains(keyword) || id.contains(keyword)) {
                    shownProfiles.add(profile);
                }
            }
        }

        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_profile, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContactProfile profile = shownProfiles.get(position);
        holder.name.setText(profile.name);

        int count = profile.contacts == null ? 0 : profile.contacts.size();
        holder.sub.setText("联系人 " + count + " 个 · ID " + profile.id.substring(0, Math.min(8, profile.id.length())));

        holder.edit.setOnClickListener(v -> listener.onEdit(profile));
        holder.delete.setOnClickListener(v -> listener.onDelete(profile));
    }

    @Override
    public int getItemCount() {
        return shownProfiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView sub;
        MaterialButton edit;
        MaterialButton delete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.textProfileName);
            sub = itemView.findViewById(R.id.textProfileSub);
            edit = itemView.findViewById(R.id.btnEditProfile);
            delete = itemView.findViewById(R.id.btnDeleteProfile);
        }
    }
}