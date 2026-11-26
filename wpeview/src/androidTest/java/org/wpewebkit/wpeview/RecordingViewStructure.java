/**
 * Copyright (C) 2026 Igalia S.L.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

package org.wpewebkit.wpeview;

import android.graphics.Matrix;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Pair;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The structure that a view gives Android's Autofill, as Android keeps it before it strips the values of sensitive
 * fields for the password service: the tests read what the view gave, which the service never sees.
 */
final class RecordingViewStructure extends ViewStructure {
    final List<RecordingViewStructure> children = new ArrayList<>();
    @Nullable
    AutofillId autofillId;
    int virtualId;
    @Nullable
    AutofillValue autofillValue;

    @Nullable
    RecordingViewStructure childWithVirtualId(int id) {
        for (RecordingViewStructure child : children) {
            if (child.virtualId == id)
                return child;
        }
        return null;
    }

    @Override
    public int addChildCount(int num) {
        int start = children.size();
        for (int i = 0; i < num; i++)
            children.add(new RecordingViewStructure());
        return start;
    }

    @Override
    public void setChildCount(int num) {
        children.clear();
        addChildCount(num);
    }

    @Override
    public int getChildCount() {
        return children.size();
    }

    @Override
    public ViewStructure newChild(int index) {
        return children.get(index);
    }

    @Override
    public ViewStructure asyncNewChild(int index) {
        return children.get(index);
    }

    @Override
    public AutofillId getAutofillId() {
        return autofillId;
    }

    @Override
    public void setAutofillId(@NonNull AutofillId id) {
        autofillId = id;
    }

    @Override
    public void setAutofillId(@NonNull AutofillId parentId, int virtualId) {
        autofillId = parentId;
        this.virtualId = virtualId;
    }

    @Override
    public void setAutofillValue(AutofillValue value) {
        autofillValue = value;
    }

    @Override
    public HtmlInfo.Builder newHtmlInfoBuilder(@NonNull String tagName) {
        return new HtmlInfo.Builder() {
            @Override
            public HtmlInfo.Builder addAttribute(@NonNull String name, @NonNull String value) {
                return this;
            }

            @Override
            public HtmlInfo build() {
                return new HtmlInfo() {
                    @Override
                    public String getTag() {
                        return tagName;
                    }

                    @Override
                    public List<Pair<String, String>> getAttributes() {
                        return Collections.emptyList();
                    }
                };
            }
        };
    }

    @Override
    public Bundle getExtras() {
        return new Bundle();
    }

    // What the tests do not read.
    @Override
    public void setId(int id, String packageName, String typeName, String entryName) {}
    @Override
    public void setDimens(int left, int top, int scrollX, int scrollY, int width, int height) {}
    @Override
    public void setTransformation(Matrix matrix) {}
    @Override
    public void setElevation(float elevation) {}
    @Override
    public void setAlpha(float alpha) {}
    @Override
    public void setVisibility(int visibility) {}
    @Override
    public void setEnabled(boolean state) {}
    @Override
    public void setClickable(boolean state) {}
    @Override
    public void setLongClickable(boolean state) {}
    @Override
    public void setContextClickable(boolean state) {}
    @Override
    public void setFocusable(boolean state) {}
    @Override
    public void setFocused(boolean state) {}
    @Override
    public void setAccessibilityFocused(boolean state) {}
    @Override
    public void setCheckable(boolean state) {}
    @Override
    public void setChecked(boolean state) {}
    @Override
    public void setSelected(boolean state) {}
    @Override
    public void setActivated(boolean state) {}
    @Override
    public void setOpaque(boolean opaque) {}
    @Override
    public void setClassName(String className) {}
    @Override
    public void setContentDescription(CharSequence contentDescription) {}
    @Override
    public void setText(CharSequence text) {}
    @Override
    public void setText(CharSequence text, int selectionStart, int selectionEnd) {}
    @Override
    public void setTextStyle(float size, int fgColor, int bgColor, int style) {}
    @Override
    public void setTextLines(int[] charOffsets, int[] baselines) {}
    @Override
    public void setHint(CharSequence hint) {}
    @Override
    public CharSequence getText() {
        return null;
    }
    @Override
    public int getTextSelectionStart() {
        return -1;
    }
    @Override
    public int getTextSelectionEnd() {
        return -1;
    }
    @Override
    public CharSequence getHint() {
        return null;
    }
    @Override
    public boolean hasExtras() {
        return false;
    }
    @Override
    public void setAutofillType(int type) {}
    @Override
    public void setAutofillHints(@Nullable String[] hint) {}
    @Override
    public void setAutofillOptions(CharSequence[] options) {}
    @Override
    public void setInputType(int inputType) {}
    @Override
    public void setDataIsSensitive(boolean sensitive) {}
    @Override
    public void asyncCommit() {}
    @Override
    public void setWebDomain(@Nullable String domain) {}
    @Override
    public void setLocaleList(LocaleList localeList) {}
    @Override
    public void setHtmlInfo(@NonNull HtmlInfo htmlInfo) {}
}
