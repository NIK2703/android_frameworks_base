/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.systemui.statusbar.phone;

import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.inputmethodservice.InputMethodService;
import android.os.RemoteException;
import android.util.AttributeSet;
import android.util.Log;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.WindowManagerGlobal;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.window.DesktopExperienceFlags;

import androidx.annotation.NonNull;

import com.android.internal.policy.SystemBarUtils;
import com.android.settingslib.Utils;
import com.android.systemui.Gefingerpoken;
import com.android.systemui.res.R;
import com.android.systemui.shade.ShadeExpandsOnStatusBarLongPress;
import com.android.systemui.shade.StatusBarLongPressGestureDetector;
import com.android.systemui.shared.rotation.FloatingRotationButton;
import com.android.systemui.shared.rotation.RotationButtonController;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.CommandQueue.Callbacks;
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays;
import com.android.systemui.statusbar.phone.userswitcher.StatusBarUserSwitcherContainer;
import com.android.systemui.statusbar.policy.Offset;
import com.android.systemui.statusbar.window.StatusBarWindowControllerStore;
import com.android.systemui.statusbar.window.StatusBarWindowView;
import com.android.systemui.user.ui.binder.StatusBarUserChipViewBinder;
import com.android.systemui.user.ui.viewmodel.StatusBarUserChipViewModel;
import com.android.systemui.util.leak.RotationUtils;
import com.android.systemui.Dependency;
import com.android.systemui.rotation.RotationPolicyWrapper;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public class PhoneStatusBarView extends FrameLayout implements Callbacks {
    private static final String TAG = "PhoneStatusBarView";

    private final CommandQueue mCommandQueue;
    private StatusBarWindowControllerStore mStatusBarWindowControllerStore;
    private boolean mShouldUpdateStatusBarHeightWhenControllerSet = false;
    private int mRotationOrientation = -1;
    private RotationButtonController mRotationButtonController;
    @Nullable
    private View mCutoutSpace;
    @Nullable
    private DisplayCutout mDisplayCutout;
    @Nullable
    private Rect mDisplaySize;
    private int mStatusBarHeight;
    @Nullable
    private Gefingerpoken mTouchEventHandler;
    @Nullable
    private BooleanSupplier mIsStatusBarInteractiveSupplier;
    @Nullable
    private HasCornerCutoutFetcher mHasCornerCutoutFetcher;
    @Nullable
    private InsetsFetcher mInsetsFetcher;
    private int mDensity;
    private float mFontScale;
    private StatusBarLongPressGestureDetector mStatusBarLongPressGestureDetector;
    private final Region mTouchableRegion = Region.obtain();

    private int mStatusBarPaddingStart = 0;
    private int mStatusBarPaddingTop = 0;
    private int mStatusBarPaddingEnd = 0;

    @Nullable
    private ViewGroup mStatusBarContents = null;
    @Nullable
    private View mCenteredArea;
    @Nullable
    private View mBatteryBar;
    @Nullable
    private View mNotificationLightsOut;

    private boolean mStatusBarInCutoutEnabled;
    private boolean mStatusBarEdgeAligned;
    private int mCutoutGravity = Gravity.TOP;
    private int mCutoutSpaceSize;
    private int mNotificationLightSize;

    /**
     * Draw this many pixels into the left/right side of the cutout to optimally use the space
     */
    private int mCutoutSideNudge = 0;

    public PhoneStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mCommandQueue = Dependency.get(CommandQueue.class);

        // Only create FRB here if there is no navbar
        if (!hasNavigationBar()) {
            final Context lightContext = new ContextThemeWrapper(context,
                    Utils.getThemeAttr(context, R.attr.lightIconTheme));
            final Context darkContext = new ContextThemeWrapper(context,
                    Utils.getThemeAttr(context, R.attr.darkIconTheme));
            final int lightIconColor =
                    Utils.getColorAttrDefaultColor(lightContext, R.attr.singleToneColor);
            final int darkIconColor =
                    Utils.getColorAttrDefaultColor(darkContext, R.attr.singleToneColor);
            final FloatingRotationButton floatingRotationButton = new FloatingRotationButton(
                    context,
                    R.string.accessibility_rotate_button, R.layout.rotate_suggestion,
                    R.id.rotate_suggestion, R.dimen.floating_rotation_button_min_margin,
                    R.dimen.rounded_corner_content_padding,
                    R.dimen.floating_rotation_button_taskbar_left_margin,
                    R.dimen.floating_rotation_button_taskbar_bottom_margin,
                    R.dimen.floating_rotation_button_diameter, R.dimen.key_button_ripple_max_width,
                    R.bool.floating_rotation_button_position_left);

            final RotationPolicyWrapper rotationPolicyWrapper =
                    Dependency.get(RotationPolicyWrapper.class);
            mRotationButtonController = new RotationButtonController(rotationPolicyWrapper,
                    lightContext, lightIconColor, darkIconColor,
                    R.drawable.ic_sysbar_rotate_button_ccw_start_0,
                    R.drawable.ic_sysbar_rotate_button_ccw_start_90,
                    R.drawable.ic_sysbar_rotate_button_cw_start_0,
                    R.drawable.ic_sysbar_rotate_button_cw_start_90,
                    () -> getDisplay().getRotation());
            mRotationButtonController.setRotationButton(floatingRotationButton, null);
        }
    }

    @Override
    public void onRotationProposal(final int rotation, boolean isValid) {
        if (mRotationButtonController != null && !hasNavigationBar()) {
            mRotationButtonController.onRotationProposal(rotation, isValid);
        }
    }

    private boolean hasNavigationBar() {
        try {
            IWindowManager windowManager = WindowManagerGlobal.getWindowManagerService();
            return windowManager.hasNavigationBar(Display.DEFAULT_DISPLAY);
        } catch (RemoteException ex) { }
        return false;
    }

    void setLongPressGestureDetector(
            StatusBarLongPressGestureDetector statusBarLongPressGestureDetector) {
        if (ShadeExpandsOnStatusBarLongPress.isEnabled()) {
            mStatusBarLongPressGestureDetector = statusBarLongPressGestureDetector;
        }
    }

    void setTouchEventHandler(Gefingerpoken handler) {
        mTouchEventHandler = handler;
    }

    void setIsStatusBarInteractiveSupplier(BooleanSupplier isStatusBarInteractiveSupplier) {
        mIsStatusBarInteractiveSupplier = isStatusBarInteractiveSupplier;
    }

    void setHasCornerCutoutFetcher(@NonNull HasCornerCutoutFetcher cornerCutoutFetcher) {
        mHasCornerCutoutFetcher = cornerCutoutFetcher;
        updateCutoutLocation();
    }

    void setInsetsFetcher(@NonNull InsetsFetcher insetsFetcher) {
        mInsetsFetcher = insetsFetcher;
        updateSafeInsets();
    }

    void init(StatusBarUserChipViewModel viewModel) {
        StatusBarUserSwitcherContainer container = findViewById(R.id.user_switcher_container);
        StatusBarUserChipViewBinder.bind(container, viewModel);
    }

    /** Updates the status bar's touchable region. */
    public void updateTouchableRegion(Region touchableRegion) {
        mTouchableRegion.set(touchableRegion);
        getViewRootImpl().setTouchableRegion(touchableRegion);
    }

    public void offsetStatusBar(Offset offset) {
        if (mStatusBarContents == null) {
            return;
        }
        if (mStatusBarContents.getRotation() == 90f) {
            mStatusBarContents.setTranslationX(-offset.getY());
            mStatusBarContents.setTranslationY(offset.getX());
        } else if (mStatusBarContents.getRotation() == -90f) {
            mStatusBarContents.setTranslationX(offset.getY());
            mStatusBarContents.setTranslationY(-offset.getX());
        } else {
            mStatusBarContents.setTranslationX(offset.getX());
            mStatusBarContents.setTranslationY(offset.getY());
        }
        invalidate();
    }

    @Override
    public void onFinishInflate() {
        super.onFinishInflate();
        mCutoutSpace = findViewById(R.id.cutout_space_view);
        mStatusBarContents = (ViewGroup) findViewById(R.id.status_bar_contents);
        mCenteredArea = findViewById(R.id.centered_area);
        mBatteryBar = findViewById(R.id.battery_bar);
        mNotificationLightsOut = findViewById(R.id.notification_lights_out);
        if (mNotificationLightsOut != null) {
            mNotificationLightSize = getResources().getDimensionPixelSize(
                    R.dimen.status_bar_icon_size_sp);
        }

        updateResources();
    }

    /** Sets whether this view should rotate its content to follow the display cutout. */
    public void setStatusBarInCutoutEnabled(boolean enabled) {
        if (mStatusBarInCutoutEnabled == enabled) {
            return;
        }
        mStatusBarInCutoutEnabled = enabled;
        if (isAttachedToWindow()) {
            updateLayoutForCutout();
        }
    }

    /** Returns whether a touch is on the physical display edge occupied by the status bar. */
    public boolean isTouchOnStatusBarDisplayEdge(float x, float y) {
        return switch (mCutoutGravity) {
            case Gravity.LEFT -> x < 1f;
            case Gravity.RIGHT -> x >= getWidth() - 1f;
            case Gravity.BOTTOM -> y >= getHeight() - 1f;
            default -> y < 1f;
        };
    }

    /** Returns the physical edge currently occupied by the status bar window. */
    public int getStatusBarGravity() {
        return mCutoutGravity;
    }

    /** Returns the long-axis length used by the rotated status-bar layout. */
    public int getStatusBarLongAxisSize() {
        return isSideGravity(mCutoutGravity) ? getHeight() : getWidth();
    }

    /** Returns the event coordinate along the status bar's long axis. */
    public float getStatusBarLongAxisCoordinate(MotionEvent event) {
        return isSideGravity(mCutoutGravity) ? event.getY() : event.getX();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        final StatusBarWindowView statusBarWindowView = findStatusBarWindowView();
        if (statusBarWindowView != null) {
            setStatusBarInCutoutEnabled(statusBarWindowView.isStatusBarInCutoutEnabled());
        }
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            updateWindowHeight();
        }

        if (mRotationButtonController != null && !hasNavigationBar()) {
            mCommandQueue.addCallback(this);
        }
    }

    @Nullable
    private StatusBarWindowView findStatusBarWindowView() {
        ViewParent parent = getParent();
        while (parent instanceof View) {
            if (parent instanceof StatusBarWindowView) {
                return (StatusBarWindowView) parent;
            }
            parent = parent.getParent();
        }
        final View root = getRootView();
        return root instanceof StatusBarWindowView ? (StatusBarWindowView) root : null;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mDisplayCutout = null;

        if (mRotationButtonController != null) {
            mCommandQueue.removeCallback(this);
        }
    }

    // Per b/300629388, we let the PhoneStatusBarView detect onConfigurationChanged to
    // updateResources, instead of letting the PhoneStatusBarViewController detect onConfigChanged
    // then notify PhoneStatusBarView.
    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateResources();

        // May trigger cutout space layout-ing
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        updateWindowHeight();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (updateDisplayParameters()) {
            updateLayoutForCutout();
            requestLayout();
        }
        return super.onApplyWindowInsets(insets);
    }


    void setExtraStatusBarPaddingDp(int startDp, int topDp, int endDp) {
        int startPx = convertToDip(startDp);
        int topPx = convertToDip(topDp);
        int endPx = convertToDip(endDp);

        if (mStatusBarPaddingStart == startPx
                && mStatusBarPaddingTop == topPx
                && mStatusBarPaddingEnd == endPx) {
            return;
        }

        mStatusBarPaddingStart = startPx;
        mStatusBarPaddingTop = topPx;
        mStatusBarPaddingEnd = endPx;

        updateResources();
        requestLayout();
    }

    private int convertToDip(int padding) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                padding,
                mContext.getResources().getDisplayMetrics()));
    }

    /**
     * @return boolean indicating if we need to update the cutout location / margins
     */
    private boolean updateDisplayParameters() {
        boolean changed = false;
        int newRotation = RotationUtils.getExactRotation(mContext);
        if (newRotation != mRotationOrientation) {
            changed = true;
            mRotationOrientation = newRotation;
        }

        WindowInsets rootInsets = getRootWindowInsets();
        DisplayCutout rootCutout = rootInsets == null ? null : rootInsets.getDisplayCutout();
        if (!Objects.equals(rootCutout, mDisplayCutout)) {
            changed = true;
            mDisplayCutout = rootCutout;
        }

        Configuration newConfiguration = mContext.getResources().getConfiguration();
        final Rect newSize = newConfiguration.windowConfiguration.getMaxBounds();
        if (!Objects.equals(newSize, mDisplaySize)) {
            changed = true;
            mDisplaySize = newSize;
        }

        int density = newConfiguration.densityDpi;
        if (density != mDensity) {
            changed = true;
            mDensity = density;
        }
        float fontScale = newConfiguration.fontScale;
        if (fontScale != mFontScale) {
            changed = true;
            mFontScale = fontScale;
        }
        return changed;
    }

    @Override
    public boolean onRequestSendAccessibilityEventInternal(View child, AccessibilityEvent event) {
        if (super.onRequestSendAccessibilityEventInternal(child, event)) {
            // The status bar is very small so augment the view that the user is touching
            // with the content of the status bar a whole. This way an accessibility service
            // may announce the current item as well as the entire content if appropriate.
            AccessibilityEvent record = AccessibilityEvent.obtain();
            onInitializeAccessibilityEvent(record);
            dispatchPopulateAccessibilityEvent(record);
            event.appendRecord(record);
            return true;
        }
        return false;
    }

    @Override
    public boolean dispatchHoverEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onHoverEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchHoverEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (mIsStatusBarInteractiveSupplier != null
                && !mIsStatusBarInteractiveSupplier.getAsBoolean()) {
            // Consume the event to prevent any calls to #onTouchEvent on status bar view or its
            // components, essentially making the status bar and its children completely
            // non-interactive.
            return true;
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Touch events outside of the touchable regions are still received by this view. Touch
        // events started within the view should not be handled to allow app handle views behind
        // the status bar to handle the event. ACTION_MOVE and ACTION_UP events outside the
        // touchable region should still be handled so that an open notification shade can be
        // correctly updated and closed.
        if (DesktopExperienceFlags.ENABLE_REMOVE_STATUS_BAR_INPUT_LAYER.isTrue()
                && event.getAction() == MotionEvent.ACTION_DOWN
                && !mTouchableRegion.contains((int) event.getRawX(), (int) event.getRawY())) {
            return false;
        }

        if (ShadeExpandsOnStatusBarLongPress.isEnabled()
                && mStatusBarLongPressGestureDetector != null) {
            mStatusBarLongPressGestureDetector.handleTouch(event);
        }
        if (mTouchEventHandler == null) {
            Log.w(
                    TAG,
                    String.format(
                            "onTouch: No touch handler provided; eating gesture at (%d,%d)",
                            (int) event.getX(),
                            (int) event.getY()
                    )
            );
            return true;
        }
        return mTouchEventHandler.onTouchEvent(event);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return mTouchEventHandler.onInterceptTouchEvent(event);
    }

    @Override
    public void setImeWindowStatus(int displayId, int vis, int backDisposition,
            boolean showImeSwitcher) {
        if (mRotationButtonController != null) {
            final boolean imeShown = (vis & InputMethodService.IME_VISIBLE) != 0;
            mRotationButtonController.getRotationButton().setCanShowRotationButton(!imeShown);
        }
    }

    public void updateResources() {
        mCutoutSideNudge = getResources().getDimensionPixelSize(
                R.dimen.display_cutout_margin_consumption);

        updateCutoutMode();
        updateStatusBarHeight();
        updateLayoutMode();
    }

    /**
     * Sets the store responsible for managing the status bar window controller.
     *
     * <p>This setter is used to facilitate dependency injection for the
     * {@link PhoneStatusBarViewController}, which receives the store via Dagger. This avoids
     * using the legacy {@link com.android.systemui.Dependency} pattern directly in the constructor.
     *
     * @param statusBarWindowControllerStore The {@link StatusBarWindowControllerStore} instance
     * to set
     */
    public void setStatusBarWindowControllerStore(
            StatusBarWindowControllerStore statusBarWindowControllerStore) {
        mStatusBarWindowControllerStore = statusBarWindowControllerStore;
        if (mShouldUpdateStatusBarHeightWhenControllerSet) {
            mShouldUpdateStatusBarHeightWhenControllerSet = false;
            updateWindowHeight();
        }
    }

    private void updateStatusBarHeight() {
        final int waterfallTopInset =
                mDisplayCutout == null ? 0 : mDisplayCutout.getWaterfallInsets().top;
        ViewGroup.LayoutParams layoutParams = getLayoutParams();
        mStatusBarHeight = Math.max(
                SystemBarUtils.getStatusBarHeight(mContext),
                getCutoutInsetForGravity(mDisplayCutout, mCutoutGravity));
        if (mStatusBarEdgeAligned && isSideGravity(mCutoutGravity)) {
            layoutParams.width = mStatusBarHeight;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            if (mStatusBarEdgeAligned || mStatusBarInCutoutEnabled) {
                // The controller's per-rotation thickness already includes the waterfall inset
                // when cutout alignment is enabled. Do not subtract it a second time.
                layoutParams.height = mStatusBarHeight;
            } else {
                layoutParams.height = mStatusBarHeight - waterfallTopInset;
            }
        }
        updateSystemIconsContainerHeight();
        updatePaddings();
        setLayoutParams(layoutParams);
    }

    private void updateSystemIconsContainerHeight() {
        View systemIconsContainer = findViewById(R.id.system_icons);
        ViewGroup.LayoutParams layoutParams = systemIconsContainer.getLayoutParams();
        int newSystemIconsHeight =
                getResources().getDimensionPixelSize(R.dimen.status_bar_system_icons_height);
        if (layoutParams.height != newSystemIconsHeight) {
            layoutParams.height = newSystemIconsHeight;
            systemIconsContainer.setLayoutParams(layoutParams);
        }
    }

    private void updatePaddings() {
        if (mStatusBarContents == null) {
            return;
        }
        int statusBarPaddingStart = getResources().getDimensionPixelSize(
                R.dimen.status_bar_padding_start);
        final int edgePadding = mStatusBarEdgeAligned
                ? getResources().getDimensionPixelSize(R.dimen.rounded_corner_content_padding)
                : 0;

        mStatusBarContents.setPaddingRelative(
                statusBarPaddingStart + edgePadding + mStatusBarPaddingStart,
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_top)
                        + mStatusBarPaddingTop,
                getResources().getDimensionPixelSize(R.dimen.status_bar_padding_end)
                        + edgePadding + mStatusBarPaddingEnd,
                0);

        if (mNotificationLightsOut != null) {
            mNotificationLightsOut.setPaddingRelative(
                    0, statusBarPaddingStart + mStatusBarPaddingStart, 0, 0);
        }

        findViewById(R.id.system_icons).setPaddingRelative(
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_start),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_top),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_end),
                getResources().getDimensionPixelSize(R.dimen.status_bar_icons_padding_bottom)
        );
    }

    private void updateLayoutForCutout() {
        updateCutoutMode();
        updateStatusBarHeight();
        updateCutoutLocation();
        updateSafeInsets();
        updateLayoutMode();
    }

    private void updateCutoutMode() {
        if (!mStatusBarInCutoutEnabled) {
            mCutoutGravity = Gravity.TOP;
            mStatusBarEdgeAligned = false;
            return;
        }

        mCutoutGravity = getCutoutGravity();
        mStatusBarEdgeAligned = mCutoutGravity != Gravity.TOP;
    }

    private int getCutoutGravity() {
        if (mDisplayCutout == null || mDisplayCutout.isEmpty()
                || isCornerCutout(mDisplayCutout)) {
            return Gravity.TOP;
        }

        int populatedSides = 0;
        int gravity = Gravity.TOP;
        for (int side : new int[] {
                Gravity.TOP, Gravity.LEFT, Gravity.RIGHT, Gravity.BOTTOM}) {
            if (!getBoundsForGravity(mDisplayCutout, side).isEmpty()) {
                populatedSides++;
                gravity = side;
            }
        }
        return populatedSides == 1 ? gravity : Gravity.TOP;
    }

    private boolean isCornerCutout(DisplayCutout cutout) {
        final int width = mDisplaySize != null
                ? mDisplaySize.width()
                : getResources().getDisplayMetrics().widthPixels;
        final int height = mDisplaySize != null
                ? mDisplaySize.height()
                : getResources().getDisplayMetrics().heightPixels;
        final Rect top = getBoundsForGravity(cutout, Gravity.TOP);
        final Rect left = getBoundsForGravity(cutout, Gravity.LEFT);
        final Rect right = getBoundsForGravity(cutout, Gravity.RIGHT);
        final Rect bottom = getBoundsForGravity(cutout, Gravity.BOTTOM);
        return (!top.isEmpty() && (top.left <= 0 || top.right >= width))
                || (!left.isEmpty() && (left.top <= 0 || left.bottom >= height))
                || (!right.isEmpty() && (right.top <= 0 || right.bottom >= height))
                || (!bottom.isEmpty() && (bottom.left <= 0 || bottom.right >= width));
    }

    private void updateCutoutLocation() {
        mCutoutSpaceSize = 0;
        // Not all layouts have a cutout (e.g., Car)
        if (mCutoutSpace == null) {
            return;
        }

        boolean hasCornerCutout;
        if (mStatusBarEdgeAligned) {
            hasCornerCutout = isCornerCutout(mDisplayCutout);
        } else if (mHasCornerCutoutFetcher != null) {
            hasCornerCutout = mHasCornerCutoutFetcher.fetchHasCornerCutout();
        } else {
            Log.e(TAG, "mHasCornerCutoutFetcher unexpectedly null");
            hasCornerCutout = true;
        }

        if (mDisplayCutout == null || mDisplayCutout.isEmpty() || hasCornerCutout) {
            mCutoutSpace.setVisibility(View.GONE);
            return;
        }

        mCutoutSpace.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) mCutoutSpace.getLayoutParams();
        Rect bounds = getBoundsForGravity(mDisplayCutout, mCutoutGravity);
        if (isSideGravity(mCutoutGravity)) {
            bounds.top = bounds.top + mCutoutSideNudge;
            bounds.bottom = bounds.bottom - mCutoutSideNudge;
            lp.width = bounds.height();
            lp.height = bounds.width();
        } else {
            bounds.left = bounds.left + mCutoutSideNudge;
            bounds.right = bounds.right - mCutoutSideNudge;
            lp.width = bounds.width();
            lp.height = bounds.height();
        }
        mCutoutSpaceSize = lp.width;
    }

    private void updateSafeInsets() {
        if (mCenteredArea != null) {
            LayoutParams centeredAreaParams = (LayoutParams) mCenteredArea.getLayoutParams();
            centeredAreaParams.leftMargin = 0;
            centeredAreaParams.rightMargin = 0;
        }
        if (mStatusBarEdgeAligned) {
            setPadding(0, 0, 0, 0);
            return;
        }
        if (mInsetsFetcher == null) {
            Log.e(TAG, "mInsetsFetcher unexpectedly null");
            return;
        }

        Insets insets = mInsetsFetcher.fetchInsets();
        setPadding(
                insets.left,
                insets.top,
                insets.right,
                getPaddingBottom());

        // Apply negative paddings to centered area layout so that we'll actually be on the center.
        Display display = getDisplay();
        final int winRotation = display != null ? display.getRotation() : Surface.ROTATION_0;
        if (mCenteredArea != null) {
            LayoutParams centeredAreaParams = (LayoutParams) mCenteredArea.getLayoutParams();
            centeredAreaParams.leftMargin =
                    winRotation == Surface.ROTATION_0 ? -insets.left : 0;
            centeredAreaParams.rightMargin =
                    winRotation == Surface.ROTATION_0 ? -insets.right : 0;
        }
    }

    private void updateLayoutMode() {
        if (mStatusBarContents == null) {
            return;
        }
        final int longAxisSize = getLongAxisSize();
        final float contentRotation = getContentRotation();

        // Keep the existing horizontal status-bar layout intact and rotate that layout as a whole.
        // This preserves all of the icon-container, chip, and clock measurement code while moving
        // the bar to a narrow side window.
        LayoutParams contentsParams = (LayoutParams) mStatusBarContents.getLayoutParams();
        contentsParams.width = mStatusBarEdgeAligned
                ? longAxisSize : ViewGroup.LayoutParams.MATCH_PARENT;
        contentsParams.height = mStatusBarEdgeAligned
                ? mStatusBarHeight : ViewGroup.LayoutParams.MATCH_PARENT;
        contentsParams.gravity = Gravity.CENTER;
        if (mStatusBarContents.getRotation() != contentRotation) {
            mStatusBarContents.setTranslationX(0f);
            mStatusBarContents.setTranslationY(0f);
        }
        float contentsWidth = contentsParams.width > 0 ? contentsParams.width : getWidth();
        float contentsHeight = contentsParams.height > 0 ? contentsParams.height : getHeight();
        mStatusBarContents.setPivotX(contentsWidth / 2f);
        mStatusBarContents.setPivotY(contentsHeight / 2f);
        mStatusBarContents.setRotation(contentRotation);
        mStatusBarContents.setLayoutParams(contentsParams);

        if (mCenteredArea != null) {
            LayoutParams centeredParams = (LayoutParams) mCenteredArea.getLayoutParams();
            centeredParams.width = contentsParams.width;
            centeredParams.height = contentsParams.height;
            // The centered clock is an overlay. Keep it centered in the bar's local (horizontal)
            // coordinate system before rotating that coordinate system for a side bar.
            centeredParams.gravity = Gravity.CENTER;
            centeredParams.leftMargin = 0;
            centeredParams.rightMargin = 0;
            float centeredWidth = centeredParams.width > 0 ? centeredParams.width : getWidth();
            float centeredHeight = centeredParams.height > 0 ? centeredParams.height : getHeight();
            mCenteredArea.setPivotX(centeredWidth / 2f);
            mCenteredArea.setPivotY(centeredHeight / 2f);
            mCenteredArea.setRotation(contentRotation);
            mCenteredArea.setLayoutParams(centeredParams);
        }

        if (mBatteryBar != null) {
            LayoutParams batteryParams = (LayoutParams) mBatteryBar.getLayoutParams();
            batteryParams.width = mStatusBarEdgeAligned
                    ? longAxisSize : ViewGroup.LayoutParams.MATCH_PARENT;
            batteryParams.gravity = mStatusBarEdgeAligned
                    ? Gravity.CENTER : Gravity.TOP | Gravity.START;
            final int batteryBarThickness = batteryParams.height > 0 ? batteryParams.height : 1;
            float batteryWidth = batteryParams.width > 0 ? batteryParams.width : getWidth();
            mBatteryBar.setPivotX(batteryWidth / 2f);
            mBatteryBar.setPivotY(batteryBarThickness / 2f);
            mBatteryBar.setRotation(contentRotation);
            mBatteryBar.setLayoutParams(batteryParams);
        }

        if (mNotificationLightsOut != null) {
            LayoutParams lightParams = (LayoutParams) mNotificationLightsOut.getLayoutParams();
            if (mStatusBarEdgeAligned) {
                lightParams.width = mNotificationLightSize;
                lightParams.height = mNotificationLightSize;
                lightParams.gravity = switch (mCutoutGravity) {
                    case Gravity.LEFT -> Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                    case Gravity.RIGHT -> Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                    case Gravity.BOTTOM -> Gravity.START | Gravity.CENTER_VERTICAL;
                    default -> Gravity.TOP | Gravity.START;
                };
            } else {
                lightParams.width = mNotificationLightSize;
                lightParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
                lightParams.gravity = Gravity.TOP | Gravity.START;
            }
            mNotificationLightsOut.setLayoutParams(lightParams);
        }
        requestLayout();
    }

    private int getLongAxisSize() {
        if (mDisplaySize != null && mDisplaySize.width() > 0 && mDisplaySize.height() > 0) {
            // In a side window the long axis of the rotated content is the current logical display
            // height, not its width. Using the wrong axis makes the horizontal status-bar layout
            // twice as wide as the side window and clips it.
            return isSideGravity(mCutoutGravity) ? mDisplaySize.height() : mDisplaySize.width();
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        return isSideGravity(mCutoutGravity) ? metrics.heightPixels : metrics.widthPixels;
    }

    private float getContentRotation() {
        if (!mStatusBarEdgeAligned) {
            return 0f;
        }
        return switch (mCutoutGravity) {
            case Gravity.LEFT -> 90f;
            case Gravity.RIGHT -> -90f;
            default -> 0f;
        };
    }

    private static boolean isSideGravity(int gravity) {
        return gravity == Gravity.LEFT || gravity == Gravity.RIGHT;
    }

    private static int getCutoutInsetForGravity(DisplayCutout cutout, int gravity) {
        if (cutout == null) {
            return 0;
        }
        return getInsetForGravity(cutout.getSafeInsets(), gravity);
    }

    private static int getInsetForGravity(Rect insets, int gravity) {
        return switch (gravity) {
            case Gravity.LEFT -> insets.left;
            case Gravity.RIGHT -> insets.right;
            case Gravity.BOTTOM -> insets.bottom;
            case Gravity.TOP -> insets.top;
            default -> 0;
        };
    }

    private static Rect getBoundsForGravity(DisplayCutout cutout, int gravity) {
        return switch (gravity) {
            case Gravity.LEFT -> cutout.getBoundingRectLeft();
            case Gravity.RIGHT -> cutout.getBoundingRectRight();
            case Gravity.BOTTOM -> cutout.getBoundingRectBottom();
            case Gravity.TOP -> cutout.getBoundingRectTop();
            default -> new Rect();
        };
    }

    private void updateWindowHeight() {
        if (StatusBarConnectedDisplays.isEnabled()) {
            // Handled directly from StatusBarWindowControllerImpl (for each display)
            return;
        }
        if (mStatusBarWindowControllerStore != null) {
            mStatusBarWindowControllerStore.getDefaultDisplay().refreshStatusBarHeight();
        } else {
            Log.e(TAG, "mStatusBarWindowControllerStore unexpectedly null");
            mShouldUpdateStatusBarHeightWhenControllerSet = true;
        }
    }

    interface HasCornerCutoutFetcher {
        boolean fetchHasCornerCutout();
    }

    interface InsetsFetcher {
        Insets fetchInsets();
    }
}
