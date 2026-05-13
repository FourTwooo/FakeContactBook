package com.fourtwo.fakecontactbook.ui;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.model.ContactFieldDefinition;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;

public class ContactFieldAdapter extends RecyclerView.Adapter<ContactFieldAdapter.ViewHolder> {
    public interface Listener {
        void onToggle(ContactFieldDefinition definition, boolean enabled);
        void onEdit(ContactFieldDefinition definition);
        void onDelete(ContactFieldDefinition definition);
    }

    private final ArrayList<ContactFieldDefinition> data = new ArrayList<>();
    private final Listener listener;

    public ContactFieldAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setData(ArrayList<ContactFieldDefinition> definitions) {
        data.clear();
        if (definitions != null) data.addAll(definitions);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_extension_field, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ContactFieldDefinition definition = data.get(position);

        holder.title.setText(TextUtils.isEmpty(definition.label) ? definition.key : definition.label);
        holder.sub.setText(definition.category + " · key=" + definition.key);

        holder.enabled.setOnCheckedChangeListener(null);
        holder.enabled.setChecked(definition.enabled);
        holder.enabled.setOnCheckedChangeListener((buttonView, isChecked) -> listener.onToggle(definition, isChecked));

        holder.edit.setOnClickListener(v -> listener.onEdit(definition));
        holder.delete.setOnClickListener(v -> listener.onDelete(definition));
    }

    @Override
    public int getItemCount() {
        return data.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView title;
        TextView sub;
        SwitchMaterial enabled;
        MaterialButton edit;
        MaterialButton delete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.textFieldTitle);
            sub = itemView.findViewById(R.id.textFieldSub);
            enabled = itemView.findViewById(R.id.switchFieldEnabled);
            edit = itemView.findViewById(R.id.btnEditField);
            delete = itemView.findViewById(R.id.btnDeleteField);
        }
    }
}