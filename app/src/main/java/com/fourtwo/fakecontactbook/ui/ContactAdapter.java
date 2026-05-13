package com.fourtwo.fakecontactbook.ui;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.widget.FrameLayout;

import com.google.android.material.imageview.ShapeableImageView;
import com.google.android.material.shape.ShapeAppearanceModel;
import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.model.FakeContact;
import com.fourtwo.fakecontactbook.model.SocialProfileInfo;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashMap;

public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {

    private final HashMap<Integer, Double> imageMatchScores = new HashMap<>();

    public interface Listener {
        void onEdit(FakeContact contact, int sourcePosition);
        void onDelete(FakeContact contact, int sourcePosition);
        void onDetail(FakeContact contact, int sourcePosition);
    }

    private final ArrayList<FakeContact> contacts = new ArrayList<>();
    private final ArrayList<Integer> sourceIndexes = new ArrayList<>();
    private final Listener listener;

    public ContactAdapter(Listener listener) {
        this.listener = listener;
    }

    public void setData(ArrayList<FakeContact> data) {
        contacts.clear();
        sourceIndexes.clear();
        imageMatchScores.clear();

        if (data != null) {
            for (int i = 0; i < data.size(); i++) {
                contacts.add(data.get(i));
                sourceIndexes.add(i);
            }
        }

        notifyDataSetChanged();
    }

    public void setFilteredData(ArrayList<FakeContact> source, ArrayList<Integer> indexes) {
        setFilteredData(source, indexes, null);
    }

    public void setFilteredData(
            ArrayList<FakeContact> source,
            ArrayList<Integer> indexes,
            HashMap<Integer, Double> scores
    ) {
        contacts.clear();
        sourceIndexes.clear();
        imageMatchScores.clear();

        if (scores != null) {
            imageMatchScores.putAll(scores);
        }

        if (source != null && indexes != null) {
            for (Integer index : indexes) {
                if (index != null && index >= 0 && index < source.size()) {
                    contacts.add(source.get(index));
                    sourceIndexes.add(index);
                }
            }
        }

        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FakeContact contact = contacts.get(position);
        int sourceIndex = position < sourceIndexes.size() ? sourceIndexes.get(position) : position;

        holder.phone.setText("手机号：" + (TextUtils.isEmpty(contact.phone) ? "未填写" : contact.phone));

//        holder.name.setText(TextUtils.isEmpty(contact.name) ? "未命名联系人" : contact.name);
        if (TextUtils.isEmpty(contact.name)) {
            holder.name.setVisibility(View.GONE);
        } else {
            holder.name.setVisibility(View.VISIBLE);
            holder.name.setText(contact.name);
        }


        if (TextUtils.isEmpty(contact.email)) {
            holder.email.setVisibility(View.GONE);
        } else {
            holder.email.setVisibility(View.VISIBLE);
            holder.email.setText("邮箱：" + contact.email);
        }

        bindSocialPreview(holder, contact, sourceIndex);

        holder.edit.setOnClickListener(v -> listener.onEdit(contact, sourceIndex));
        holder.delete.setOnClickListener(v -> listener.onDelete(contact, sourceIndex));
        holder.detail.setOnClickListener(v -> listener.onDetail(contact, sourceIndex));
    }

    private void bindSocialPreview(ViewHolder holder, FakeContact contact, int sourceIndex) {
        holder.socialContainer.removeAllViews();

        Double imageScore = imageMatchScores.get(sourceIndex);
        boolean hasImageScore = imageScore != null;

        ArrayList<SocialProfileInfo> socialList = contact.getSortedSocialProfiles();

        if (!hasImageScore && socialList.isEmpty()) {
            holder.socialScroll.setVisibility(View.GONE);
            holder.detail.setVisibility(View.GONE);
            return;
        }

        holder.socialScroll.setVisibility(View.VISIBLE);
        holder.detail.setVisibility(socialList.isEmpty() ? View.GONE : View.VISIBLE);

        Context context = holder.itemView.getContext();

        if (hasImageScore) {
            holder.socialContainer.addView(createImageScoreChip(context, imageScore));
        }

        int limit = Math.min(3, socialList.size());
        for (int i = 0; i < limit; i++) {
            holder.socialContainer.addView(createSocialChip(context, socialList.get(i)));
        }

        if (socialList.size() > limit) {
            TextView more = new TextView(context);
            more.setText("还有 " + (socialList.size() - limit) + " 个平台");
            more.setTextSize(12);
            more.setGravity(Gravity.CENTER_VERTICAL);
            more.setPadding(dp(context, 10), 0, dp(context, 10), 0);

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(context, 36)
            );
            params.setMargins(dp(context, 6), 0, 0, 0);
            holder.socialContainer.addView(more, params);
        }
    }

    private View createImageScoreChip(Context context, double score) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(context, 10), 0, dp(context, 10), 0);

        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(context, 38)
        );
        chipParams.setMargins(0, 0, dp(context, 8), 0);
        chip.setLayoutParams(chipParams);

        ImageView icon = new ImageView(context);
        icon.setImageResource(android.R.drawable.ic_menu_camera);
        icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

        chip.addView(icon, new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)));

        TextView text = new TextView(context);
        text.setText("识别率 " + Math.round(score * 100) + "%");
        text.setTextSize(12);
        text.setSingleLine(true);
        text.setPadding(dp(context, 6), 0, 0, 0);

        chip.addView(text, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        return chip;
    }

    private View createAvatarWithPlatformBadge(Context context, SocialProfileInfo info) {
        int wrapperSize = dp(context, 42);
        int avatarSize = dp(context, 34);
        int badgeSize = dp(context, 18);

        FrameLayout wrapper = new FrameLayout(context);

        LinearLayout.LayoutParams wrapperParams = new LinearLayout.LayoutParams(
                wrapperSize,
                wrapperSize
        );
        wrapper.setLayoutParams(wrapperParams);

        ShapeableImageView avatarView = new ShapeableImageView(context);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setShapeAppearanceModel(
                ShapeAppearanceModel.builder()
                        .setAllCornerSizes(avatarSize / 2f)
                        .build()
        );
        avatarView.setStrokeColor(ColorStateList.valueOf(Color.WHITE));
        avatarView.setStrokeWidth(dp(context, 1));

        Bitmap avatar = info.decodeAvatarBitmap();

        if (avatar != null) {
            /*
             * 真实用户头像。
             */
            avatarView.setImageBitmap(avatar);
        } else {
            /*
             * UI 占位头像。
             * 注意：这个默认头像只用于列表展示，不写入 payload，
             * 所以不会参与图片识别。
             */
            avatarView.setImageResource(android.R.drawable.ic_menu_myplaces);
            avatarView.setBackground(makeOvalDrawable(
                    Color.rgb(238, 240, 244),
                    Color.WHITE,
                    dp(context, 1)
            ));
            avatarView.setPadding(
                    dp(context, 7),
                    dp(context, 7),
                    dp(context, 7),
                    dp(context, 7)
            );
        }

        FrameLayout.LayoutParams avatarParams = new FrameLayout.LayoutParams(
                avatarSize,
                avatarSize,
                Gravity.CENTER
        );
        wrapper.addView(avatarView, avatarParams);

        ShapeableImageView platformBadge = new ShapeableImageView(context);
        platformBadge.setScaleType(ImageView.ScaleType.CENTER_CROP);
        platformBadge.setShapeAppearanceModel(
                ShapeAppearanceModel.builder()
                        .setAllCornerSizes(badgeSize / 2f)
                        .build()
        );
        platformBadge.setBackground(makeOvalDrawable(
                Color.WHITE,
                Color.WHITE,
                dp(context, 1)
        ));
        platformBadge.setPadding(dp(context, 2), dp(context, 2), dp(context, 2), dp(context, 2));

        try {
            PackageManager pm = context.getPackageManager();
            Drawable icon = pm.getApplicationIcon(info.packageName);
            platformBadge.setImageDrawable(icon);
        } catch (Exception ignored) {
            platformBadge.setImageResource(android.R.drawable.sym_def_app_icon);
        }

        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                badgeSize,
                badgeSize,
                Gravity.BOTTOM | Gravity.END
        );
        badgeParams.setMargins(0, 0, dp(context, 1), dp(context, 1));
        wrapper.addView(platformBadge, badgeParams);

        return wrapper;
    }

    private GradientDrawable makeOvalDrawable(int fillColor, int strokeColor, int strokeWidth) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(fillColor);

        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }

        return drawable;
    }

    private View createSocialChip(Context context, SocialProfileInfo info) {
        LinearLayout chip = new LinearLayout(context);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(context, 8), 0, dp(context, 10), 0);

        LinearLayout.LayoutParams chipParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(context, 44)
        );
        chipParams.setMargins(0, 0, dp(context, 8), 0);
        chip.setLayoutParams(chipParams);

        /*
         * 新布局：
         * 用户头像作为主图；
         * 平台图标作为右下角角标；
         * 没有用户头像时，用默认空头像占位。
         */
        chip.addView(createAvatarWithPlatformBadge(context, info));

        TextView title = new TextView(context);
        title.setText(info.getPreviewTitle(context));
        title.setTextSize(12);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        textParams.setMargins(dp(context, 8), 0, 0, 0);
        chip.addView(title, textParams);

        return chip;
    }

    @Override
    public int getItemCount() {
        return contacts.size();
    }

    private int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView name;
        TextView phone;
        TextView email;
        HorizontalScrollView socialScroll;
        LinearLayout socialContainer;
        MaterialButton edit;
        MaterialButton delete;
        MaterialButton detail;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.textContactName);
            phone = itemView.findViewById(R.id.textContactPhone);
            email = itemView.findViewById(R.id.textContactEmail);
            socialScroll = itemView.findViewById(R.id.socialPreviewScroll);
            socialContainer = itemView.findViewById(R.id.socialPreviewContainer);
            edit = itemView.findViewById(R.id.btnEditContact);
            delete = itemView.findViewById(R.id.btnDeleteContact);
            detail = itemView.findViewById(R.id.btnSocialDetail);
        }
    }
}