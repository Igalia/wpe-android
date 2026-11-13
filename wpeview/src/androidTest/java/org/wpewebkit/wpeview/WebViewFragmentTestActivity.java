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

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * An activity that shows a page that the app keeps in its fragments, as an app that keeps its pages in a ViewModel: the
 * view of each {@link PageFragment} shows the page as soon as the fragment makes it.
 */
public class WebViewFragmentTestActivity extends FragmentActivity {
    // The view that holds the fragments.
    final int containerId = View.generateViewId();
    // The page that the fragments show, which the test sets.
    @Nullable
    WebPage page;
    // The views that the fragments made, in order, whether a view in the window showed the page as each of them took
    // it, and what setPage() threw in them.
    final List<WebView> views = new ArrayList<>();
    final List<Boolean> takenFromViewInWindow = new ArrayList<>();
    final List<RuntimeException> errors = new ArrayList<>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout container = new FrameLayout(this);
        container.setId(containerId);
        setContentView(container);
    }

    /** A fragment whose view shows the page of the activity from the moment the fragment makes it. */
    public static class PageFragment extends Fragment {
        @Override
        public @NonNull View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                                          @Nullable Bundle savedInstanceState) {
            return WebView.createEmpty(requireContext());
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            WebViewFragmentTestActivity activity = (WebViewFragmentTestActivity)requireActivity();
            WebView webView = (WebView)view;
            activity.views.add(webView);
            WebView shown = activity.page != null ? activity.page.getView() : null;
            activity.takenFromViewInWindow.add(shown != null && shown.isAttachedToWindow());
            try {
                webView.setPage(activity.page);
            } catch (RuntimeException exception) {
                activity.errors.add(exception);
            }
        }
    }
}
