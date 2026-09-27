/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.window;

import static android.view.WindowInsets.Type.systemBars;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.util.AttributeSet;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.systemui.compose.ComposeInitializer;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.core.StatusBarRootModernization;
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController;
import com.android.systemui.statusbar.phone.PhoneStatusBarView;

/**
 * Status bar view
 * We now extend WindowRootView so that we can host Compose views
 */
public class StatusBarWindowView extends FrameLayout {

    public static final String TAG = "PhoneStatusBarWindowView";
    public static final boolean DEBUG = false;

    private int mLeftInset = 0;
    private int mRightInset = 0;
    private int mTopInset = 0;
    private int mBottomInset = 0;
    private Insets mLastSystemBarInsets = Insets.NONE;
    private int mLastWaterfallInset;
    private boolean mHasLastInsets;

    private float mTouchDownY = 0;

    @Nullable private StatusBarConfigurationController mConfigurationController;
    private boolean mStatusBarInCutoutEnabled;
    private int mStatusBarGravity = Gravity.TOP;

    public StatusBarWindowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setClipChildren(false);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();

        if (StatusBarRootModernization.isEnabled()) {
            ComposeInitializer.INSTANCE.onAttachedToWindow(this);
        }
    }

    @Override
    public void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        if (StatusBarRootModernization.isEnabled()) {
            ComposeInitializer.INSTANCE.onDetachedFromWindow(this);
        }
    }

    /**
     * Sets the {@link StatusBarConfigurationController} that is associated with the display that
     * this view is attached to.
     */
    public void setStatusBarConfigurationController(
            @NonNull StatusBarConfigurationController configurationController) {
        mConfigurationController = configurationController;
    }

    /**
     * Updates whether the status-bar content should be aligned with the current display cutout.
     * The window controller remains the source of truth for the window frame and its insets.
     */
    public void setStatusBarInCutoutEnabled(boolean enabled) {
        mStatusBarInCutoutEnabled = enabled;
        if (enabled) {
            setPadding(0, 0, 0, 0);
        }
        if (mHasLastInsets) {
            applyInsetMargins(mLastSystemBarInsets, mLastWaterfallInset);
        } else {
            applyMargins();
        }
        View statusBar = findViewById(R.id.status_bar);
        if (statusBar instanceof PhoneStatusBarView) {
            ((PhoneStatusBarView) statusBar).setStatusBarInCutoutEnabled(enabled);
        }
    }

    /** Returns whether cutout-aligned status-bar content is enabled. */
    public boolean isStatusBarInCutoutEnabled() {
        return mStatusBarInCutoutEnabled;
    }

    /** Updates the gravity currently used by the status-bar window. */
    public void setStatusBarGravity(int gravity) {
        mStatusBarGravity = gravity;
        if (mHasLastInsets) {
            applyInsetMargins(mLastSystemBarInsets, mLastWaterfallInset);
        } else {
            applyMargins();
        }
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        StatusBarConfigurationController configurationController = mConfigurationController;
        if (configurationController != null) {
            configurationController.onConfigurationChanged(newConfig);
        }
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets windowInsets) {
        final Insets insets = windowInsets.getInsetsIgnoringVisibility(systemBars());
        final DisplayCutout displayCutout = windowInsets.getDisplayCutout();
        final int waterfallInset = displayCutout == null
                ? 0 : displayCutout.getWaterfallInsets().top;
        mLastSystemBarInsets = insets;
        mLastWaterfallInset = waterfallInset;
        mHasLastInsets = true;
        applyInsetMargins(insets, waterfallInset);
        return windowInsets;
    }

    private void applyInsetMargins(Insets insets, int waterfallInset) {
        if (mStatusBarInCutoutEnabled) {
            // The window itself is the side/top/bottom cutout-aligned insets source. Do not add
            // that source back as a margin on the edge occupied by this window. Preserve unrelated
            // navigation-bar insets on the other edges so gesture navigation and 3-button
            // navigation do not make the rotated content disappear.
            setPadding(0, 0, 0, 0);
            switch (mStatusBarGravity) {
                case Gravity.LEFT:
                    mLeftInset = 0;
                    mRightInset = insets.right;
                    mTopInset = insets.top;
                    mBottomInset = insets.bottom;
                    break;
                case Gravity.RIGHT:
                    mLeftInset = insets.left;
                    mRightInset = 0;
                    mTopInset = insets.top;
                    mBottomInset = insets.bottom;
                    break;
                case Gravity.BOTTOM:
                    mLeftInset = insets.left;
                    mRightInset = insets.right;
                    mTopInset = insets.top;
                    mBottomInset = 0;
                    break;
                case Gravity.TOP:
                default:
                    mLeftInset = insets.left;
                    mRightInset = insets.right;
                    mTopInset = 0;
                    mBottomInset = 0;
                    break;
            }
        } else {
            mLeftInset = insets.left;
            mRightInset = insets.right;
            mTopInset = waterfallInset;
            mBottomInset = 0;
        }
        applyMargins();
    }

    private void applyMargins() {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            View child = getChildAt(i);
            if (child.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) child.getLayoutParams();
                if (lp.rightMargin != mRightInset || lp.leftMargin != mLeftInset
                        || lp.topMargin != mTopInset || lp.bottomMargin != mBottomInset) {
                    lp.rightMargin = mRightInset;
                    lp.leftMargin = mLeftInset;
                    lp.topMargin = mTopInset;
                    lp.bottomMargin = mBottomInset;
                    child.requestLayout();
                }
            }
        }
    }
}
