package com.fourtwo.fakecontactbook.model;

import android.graphics.drawable.Drawable;

public class InstalledApp {
    public final String label;
    public final String packageName;
    public final Drawable icon;

    public InstalledApp(String label, String packageName, Drawable icon) {
        this.label = label == null ? packageName : label;
        this.packageName = packageName;
        this.icon = icon;
    }
}
