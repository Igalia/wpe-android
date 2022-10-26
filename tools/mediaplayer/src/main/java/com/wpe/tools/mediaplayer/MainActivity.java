/**
 * Copyright (C) 2022 Igalia S.L. <info@igalia.com>
 *   Author: Loïc Le Page <llepage@igalia.com>
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

package com.wpe.tools.mediaplayer;

import android.content.res.AssetManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.wpe.wpeview.WPEView;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {
    private static final String webAppName = "mediaplayer";

    @NonNull
    private String getHtmlContent() throws IOException {
        AssetManager assets = getAssets();
        try (InputStream in = assets.open("index.html")) {
            byte[] buffer = new byte[in.available()];
            in.read(buffer);
            return new String(buffer, StandardCharsets.UTF_8);
        }
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        try {
            String content = getHtmlContent();
            WPEView view = findViewById(R.id.wpe_view);
            view.getPage().getPageSettings().setAllowFileUrls(true);
            view.loadHtml(content, "file:///");
        } catch (IOException ex) {
            String message = "Cannot initialize web application";
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            Log.e(webAppName, message, ex);
            finish();
        }
    }

    @SuppressWarnings({"InlinedApi", "deprecation"})
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            View rootView = findViewById(android.R.id.content);
            if (rootView != null) {
                if (Build.VERSION.SDK_INT >= 30) {
                    rootView.getWindowInsetsController().hide(WindowInsets.Type.statusBars() |
                                                              WindowInsets.Type.navigationBars());
                } else {
                    rootView = rootView.getRootView();
                    if (rootView != null) {
                        rootView.setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_LOW_PROFILE | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
                    }
                }
            }
        }
    }
}
