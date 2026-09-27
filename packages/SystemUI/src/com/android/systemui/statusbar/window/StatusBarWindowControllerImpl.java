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

import static android.view.WindowInsets.Type.mandatorySystemGestures;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsets.Type.tappableElement;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;

import static com.android.systemui.util.leak.RotationUtils.ROTATION_LANDSCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_NONE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_SEASCAPE;
import static com.android.systemui.util.leak.RotationUtils.ROTATION_UPSIDE_DOWN;

import android.content.Context;
import android.content.res.Configuration;
import android.database.ExecutorContentObserver;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.IWindowManager;
import android.view.InsetsFrameProvider;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;

import androidx.annotation.NonNull;

import com.android.systemui.animation.ActivityTransitionAnimator;
import com.android.systemui.animation.DelegateTransitionAnimatorController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.fragments.FragmentHostManager;
import com.android.systemui.fragments.FragmentService;
import com.android.systemui.log.LogBuffer;
import com.android.systemui.log.core.LogLevel;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays;
import com.android.systemui.statusbar.core.StatusBarRootModernization;
import com.android.systemui.statusbar.core.StatusBarForDesktop;
import com.android.systemui.statusbar.data.repository.StatusBarConfigurationController;
import com.android.systemui.statusbar.layout.StatusBarContentInsetsChangedListener;
import com.android.systemui.statusbar.layout.StatusBarContentInsetsProvider;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.systemui.statusbar.window.StatusBarWindowModule.InternalWindowViewInflater;
import com.android.systemui.unfold.UnfoldTransitionProgressProvider;
import com.android.systemui.unfold.util.JankMonitorTransitionProgressListener;

import lineageos.providers.LineageSettings;

import dagger.assisted.Assisted;
import dagger.assisted.AssistedFactory;
import dagger.assisted.AssistedInject;

import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Encapsulates all logic for the status bar window state management.
 */
public class StatusBarWindowControllerImpl implements StatusBarWindowController {
    private static final String TAG = "StatusBarWindowController";
    private static final boolean DEBUG = false;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final StatusBarConfigurationController mStatusBarConfigurationController;
    private final IWindowManager mIWindowManager;
    private final StatusBarContentInsetsProvider mContentInsetsProvider;
    private final Executor mMainExecutor;
    private final LogBuffer mLogBuffer;
    private final int mDisplayId;
    private int mBarHeight = -1;
    private final State mCurrentState = new State();
    private boolean mIsAttached;
    private boolean mStatusBarInCutoutEnabled;
    private boolean mStatusBarInCutoutObserverRegistered;
    private final ExecutorContentObserver mStatusBarInCutoutObserver;
    private final StatusBarContentInsetsChangedListener mStatusBarLocationsCallback =
            this::calculateStatusBarLocationsForAllRotations;

    private final StatusBarWindowView mStatusBarWindowView;
    private final FragmentService mFragmentService;
    // The container in which we should run launch animations started from the status bar and
    //   expanding into the opening window.
    private final ViewGroup mLaunchAnimationContainer;
    private WindowManager.LayoutParams mLp;
    private final WindowManager.LayoutParams mLpChanged;
    private final Binder mInsetsSourceOwner = new Binder();

    private final ConfigurationController.ConfigurationListener mConfigurationListener =
            new ConfigurationController.ConfigurationListener() {
                @Override
                public void onConfigChanged(Configuration newConfig) {
                    refreshStatusBarHeight();
                }
            };

    @AssistedInject
    public StatusBarWindowControllerImpl(
            @Assisted Context context,
            @InternalWindowViewInflater StatusBarWindowViewInflater statusBarWindowViewInflater,
            @Assisted WindowManager windowManager,
            @Assisted StatusBarConfigurationController statusBarConfigurationController,
            IWindowManager iWindowManager,
            @Assisted StatusBarContentInsetsProvider contentInsetsProvider,
            FragmentService fragmentService,
            Optional<UnfoldTransitionProgressProvider> unfoldTransitionProgressProvider,
            @Main Executor mainExecutor,
            @StatusBarWindowLog LogBuffer logBuffer,
            @Assisted int displayId) {
        mContext = context;
        mDisplayId = displayId;
        mWindowManager = windowManager;
        mStatusBarConfigurationController = statusBarConfigurationController;
        mIWindowManager = iWindowManager;
        mContentInsetsProvider = contentInsetsProvider;
        mMainExecutor = mainExecutor;
        mLogBuffer = logBuffer;
        mStatusBarInCutoutEnabled = readStatusBarInCutoutSetting();
        mStatusBarWindowView = statusBarWindowViewInflater.inflate(context);
        mStatusBarWindowView.setStatusBarInCutoutEnabled(isCutoutAlignmentActive());
        mStatusBarInCutoutObserver = new ExecutorContentObserver(mMainExecutor) {
            @Override
            public void onChange(boolean selfChange) {
                refreshStatusBarInCutoutMode();
            }
        };
        mFragmentService = fragmentService;
        mLaunchAnimationContainer = mStatusBarWindowView.findViewById(
                R.id.status_bar_launch_animation_container);
        mLpChanged = new WindowManager.LayoutParams();

        if (mBarHeight < 0) {
            mBarHeight = getCurrentBarThickness();
        }
        unfoldTransitionProgressProvider.ifPresent(
                unfoldProgressProvider -> unfoldProgressProvider.addCallback(
                        new JankMonitorTransitionProgressListener(
                                /* attachedViewProvider=*/ () -> mStatusBarWindowView)));
    }

    @Override
    public int getStatusBarHeight() {
        return mBarHeight;
    }

    private boolean readStatusBarInCutoutSetting() {
        if (mDisplayId != Display.DEFAULT_DISPLAY) {
            return false;
        }
        try {
            return LineageSettings.System.getIntForUser(
                    mContext.getContentResolver(),
                    LineageSettings.System.STATUS_BAR_IN_CUTOUT,
                    0,
                    UserHandle.USER_CURRENT) != 0;
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to read cutout-aligned status bar setting", e);
            return false;
        }
    }

    private boolean isCutoutAlignmentActive() {
        return mStatusBarInCutoutEnabled && !StatusBarForDesktop.isEnabled();
    }

    private int getCurrentBarThickness() {
        final int rotation = mContext.getDisplay().getRotation();
        return getBarThicknessForRotation(
                getBarGravityForRotation(rotation), getDisplayCutoutForRotation(rotation));
    }

    private void refreshStatusBarInCutoutMode() {
        mStatusBarInCutoutEnabled = readStatusBarInCutoutSetting();
        mBarHeight = getCurrentBarThickness();
        mStatusBarWindowView.setStatusBarInCutoutEnabled(isCutoutAlignmentActive());
        mContentInsetsProvider.refresh();
        if (mIsAttached && mLp != null) {
            // Re-read the cutout and rebuild every rotation. A settings notification can arrive
            // together with a display-state change, and per-rotation parameters must not alias the
            // currently installed LayoutParams.
            refreshStatusBarHeight();
        }
    }

    private void registerStatusBarInCutoutObserver() {
        if (mDisplayId != Display.DEFAULT_DISPLAY
                || mStatusBarInCutoutObserverRegistered) {
            return;
        }
        mContext.getContentResolver().registerContentObserver(
                LineageSettings.System.getUriFor(LineageSettings.System.STATUS_BAR_IN_CUTOUT),
                false,
                mStatusBarInCutoutObserver,
                UserHandle.USER_CURRENT);
        mStatusBarInCutoutObserverRegistered = true;
    }

    private void unregisterStatusBarInCutoutObserver() {
        if (!mStatusBarInCutoutObserverRegistered) {
            return;
        }
        mContext.getContentResolver().unregisterContentObserver(mStatusBarInCutoutObserver);
        mStatusBarInCutoutObserverRegistered = false;
    }

    @Override
    public void refreshStatusBarHeight() {
        Trace.beginSection("StatusBarWindowController#refreshStatusBarHeight");
        try {
            mBarHeight = getCurrentBarThickness();
            if (mIsAttached && mLp != null) {
                refreshBarGeometry();
                apply(mCurrentState);
            }

            if (DEBUG) Log.v(TAG, "defineSlots");
        } finally {
            Trace.endSection();
        }
    }

    private void refreshBarGeometry() {
        final int currentRotation = mContext.getDisplay().getRotation();
        mLpChanged.gravity = getBarGravityForRotation(currentRotation);
        rebuildPerRotationParams();
    }

    /**
     * LayoutParams.copyFrom() intentionally aliases paramsForRotation. Build a fresh array before
     * changing any nested rotation parameters so a future-rotation cutout update cannot be hidden by
     * that aliasing and skipped by WindowManager.updateViewLayout().
     */
    private void rebuildPerRotationParams() {
        WindowManager.LayoutParams[] params = new WindowManager.LayoutParams[4];
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            params[rot] = getBarLayoutParamsForRotation(rot);
        }
        mLpChanged.paramsForRotation = params;
    }

    @Override
    public void attach() {
        registerStatusBarInCutoutObserver();
        refreshStatusBarInCutoutMode();
        if (StatusBarConnectedDisplays.isEnabled()) {
            mStatusBarWindowView.setStatusBarConfigurationController(
                    mStatusBarConfigurationController);
        }
        // Now that the status bar window encompasses the sliding panel and its
        // translucent backdrop, the entire thing is made TRANSLUCENT and is
        // hardware-accelerated.
        Trace.beginSection("StatusBarWindowController.getBarLayoutParams");
        mLp = getBarLayoutParams(mContext.getDisplay().getRotation());
        Trace.endSection();
        mLpChanged.copyFrom(mLp);
        mStatusBarWindowView.setStatusBarGravity(mLp.gravity);

        try {
            mWindowManager.addView(mStatusBarWindowView, mLp);
        } catch (WindowManager.InvalidDisplayException e) {
            // Wrapping this in a try/catch to avoid crashes when a display is instantly removed
            // after being added, and initialization hasn't finished yet.
            Log.e(
                    TAG,
                    "Unable to add view to WindowManager. Display with id "
                            + mContext.getDisplayId()
                            + " doesn't exist anymore.",
                    e);
        }
        if (StatusBarConnectedDisplays.isEnabled()) {
            mStatusBarConfigurationController.addCallback(mConfigurationListener);
            refreshStatusBarHeight();
        }

        mContentInsetsProvider.addCallback(mStatusBarLocationsCallback);
        calculateStatusBarLocationsForAllRotations();
        mIsAttached = true;
        apply(mCurrentState);
    }

    @Override
    public void stop() {
        StatusBarConnectedDisplays.unsafeAssertInNewMode();
        mIsAttached = false;
        unregisterStatusBarInCutoutObserver();
        mContentInsetsProvider.removeCallback(mStatusBarLocationsCallback);

        try {
            mWindowManager.removeView(mStatusBarWindowView);
        } catch (IllegalArgumentException e) {
            // Wrapping this in a try/catch to avoid crashes when a display is instantly removed
            // after being added, and initialization hasn't finished yet.
            // When that happens, adding the View to WindowManager fails, and therefore removing
            // it here will fail too, since it wasn't added in the first place.
            Log.e(TAG, "Failed to remove View from WindowManager. View was not attached", e);
        }
        mStatusBarConfigurationController.removeCallback(mConfigurationListener);
        if (StatusBarRootModernization.isEnabled()) {
            return;
        }
        // Fragment transactions need to happen on the main thread.
        mMainExecutor.execute(() -> mFragmentService.removeAndDestroy(mStatusBarWindowView));
    }

    @Override
    public void addViewToWindow(@NonNull View view, @NonNull ViewGroup.LayoutParams layoutParams) {
        mStatusBarWindowView.addView(view, layoutParams);
    }

    @NonNull
    @Override
    public View getBackgroundView() {
        return mStatusBarWindowView.findViewById(R.id.status_bar_container);
    }

    @NonNull
    @Override
    public FragmentHostManager getFragmentHostManager() {
        return mFragmentService.getFragmentHostManager(mStatusBarWindowView);
    }

    @NonNull
    @Override
    public Optional<ActivityTransitionAnimator.Controller> wrapAnimationControllerIfInStatusBar(
            @NonNull View rootView,
            @NonNull ActivityTransitionAnimator.Controller animationController) {
        if (rootView != mStatusBarWindowView) {
            return Optional.empty();
        }

        animationController.setTransitionContainer(mLaunchAnimationContainer);
        return Optional.of(
                new DelegateTransitionAnimatorController(animationController) {
                    @Override
                    public void onTransitionAnimationStart(boolean isExpandingFullyAbove) {
                        getDelegate().onTransitionAnimationStart(isExpandingFullyAbove);
                        setLaunchAnimationRunning(
                                true, /* source= */ animationController.toString());
                    }

                    @Override
                    public void onTransitionAnimationEnd(boolean isExpandingFullyAbove) {
                        getDelegate().onTransitionAnimationEnd(isExpandingFullyAbove);
                        setLaunchAnimationRunning(
                                false, /* source= */ animationController.toString());
                    }
                });
    }

    private WindowManager.LayoutParams getBarLayoutParams(int rotation) {
        WindowManager.LayoutParams lp = getBarLayoutParamsForRotation(rotation);
        lp.paramsForRotation = new WindowManager.LayoutParams[4];
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            lp.paramsForRotation[rot] = getBarLayoutParamsForRotation(rot);
        }
        return lp;
    }

    private WindowManager.LayoutParams getBarLayoutParamsForRotation(int rotation) {
        final int gravity = getBarGravityForRotation(rotation);
        final DisplayCutout cutout = getDisplayCutoutForRotation(rotation);
        final int thickness = getBarThicknessForRotation(gravity, cutout);
        final boolean sideAligned = isSideGravity(gravity);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                sideAligned ? thickness : WindowManager.LayoutParams.MATCH_PARENT,
                sideAligned ? WindowManager.LayoutParams.MATCH_PARENT : thickness,
                WindowManager.LayoutParams.TYPE_STATUS_BAR,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
                PixelFormat.TRANSLUCENT);
        lp.privateFlags |= PRIVATE_FLAG_COLOR_SPACE_AGNOSTIC;
        lp.token = new Binder();
        lp.gravity = gravity;
        lp.setFitInsetsTypes(0 /* types */);
        String titleSuffix =
                mDisplayId == Display.DEFAULT_DISPLAY ? "" : "(displayId=" + mDisplayId + ")";
        lp.setTitle("StatusBar" + titleSuffix);
        lp.packageName = mContext.getPackageName();
        lp.layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        final InsetsFrameProvider gestureInsetsProvider =
                new InsetsFrameProvider(mInsetsSourceOwner, 0, mandatorySystemGestures());
        final int safeTouchRegionSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.display_cutout_touchable_region_size);
        if (safeTouchRegionSize > 0) {
            gestureInsetsProvider.setMinimalInsetsSizeInDisplayCutoutSafe(
                    getInsets(safeTouchRegionSize, gravity));
        }
        lp.providedInsets = new InsetsFrameProvider[] {
                new InsetsFrameProvider(mInsetsSourceOwner, 0, statusBars())
                        .setInsetsSize(getInsets(thickness, gravity)),
                new InsetsFrameProvider(mInsetsSourceOwner, 0, tappableElement())
                        .setInsetsSize(getInsets(thickness, gravity)),
                gestureInsetsProvider
        };
        return lp;
    }

    private DisplayCutout getDisplayCutoutForRotation(int rotation) {
        final Display display = mContext.getDisplay();
        final DisplayCutout cutout = display.getCutout();
        if (cutout == null) {
            return null;
        }
        final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        return cutout.getRotated(
                metrics.widthPixels, metrics.heightPixels, display.getRotation(), rotation);
    }

    private int getBarGravityForRotation(int rotation) {
        if (!isCutoutAlignmentActive()) {
            return Gravity.TOP;
        }
        final DisplayCutout cutout = getDisplayCutoutForRotation(rotation);
        if (cutout == null || cutout.isEmpty()) {
            return Gravity.TOP;
        }

        int populatedSides = 0;
        int gravity = Gravity.TOP;
        for (int side : new int[] {
                Gravity.TOP, Gravity.LEFT, Gravity.RIGHT, Gravity.BOTTOM}) {
            if (!getBoundsForGravity(cutout, side).isEmpty()) {
                populatedSides++;
                gravity = side;
            }
        }
        if (populatedSides != 1 || isCornerCutout(cutout, rotation)) {
            return Gravity.TOP;
        }
        return gravity;
    }

    private boolean isCornerCutout(DisplayCutout cutout, int rotation) {
        final Display display = mContext.getDisplay();
        final DisplayMetrics metrics = mContext.getResources().getDisplayMetrics();
        final int rotationDelta = (rotation - display.getRotation() + 4) % 4;
        final boolean swapDimensions = (rotationDelta % 2) != 0;
        final int width = swapDimensions ? metrics.heightPixels : metrics.widthPixels;
        final int height = swapDimensions ? metrics.widthPixels : metrics.heightPixels;
        final Rect top = getBoundsForGravity(cutout, Gravity.TOP);
        final Rect left = getBoundsForGravity(cutout, Gravity.LEFT);
        final Rect right = getBoundsForGravity(cutout, Gravity.RIGHT);
        final Rect bottom = getBoundsForGravity(cutout, Gravity.BOTTOM);
        return (!top.isEmpty() && (top.left <= 0 || top.right >= width))
                || (!left.isEmpty() && (left.top <= 0 || left.bottom >= height))
                || (!right.isEmpty() && (right.top <= 0 || right.bottom >= height))
                || (!bottom.isEmpty() && (bottom.left <= 0 || bottom.right >= width));
    }

    private int getBarThicknessForRotation(int gravity, DisplayCutout cutout) {
        final int defaultSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_height_default);
        if (cutout == null || cutout.isEmpty()) {
            return defaultSize;
        }
        final Rect safeInsets = cutout.getSafeInsets();
        final Insets waterfallInsets = cutout.getWaterfallInsets();
        final int cutoutInset = getInsetForGravity(safeInsets, gravity);
        final int waterfallInset = getInsetForGravity(waterfallInsets, gravity);
        return Math.max(cutoutInset, defaultSize + waterfallInset);
    }

    private static Rect getBoundsForGravity(DisplayCutout cutout, int gravity) {
        switch (gravity) {
            case Gravity.LEFT:
                return cutout.getBoundingRectLeft();
            case Gravity.RIGHT:
                return cutout.getBoundingRectRight();
            case Gravity.BOTTOM:
                return cutout.getBoundingRectBottom();
            case Gravity.TOP:
            default:
                return cutout.getBoundingRectTop();
        }
    }

    private static int getInsetForGravity(Rect insets, int gravity) {
        switch (gravity) {
            case Gravity.LEFT:
                return insets.left;
            case Gravity.RIGHT:
                return insets.right;
            case Gravity.BOTTOM:
                return insets.bottom;
            case Gravity.TOP:
            default:
                return insets.top;
        }
    }

    private static int getInsetForGravity(Insets insets, int gravity) {
        switch (gravity) {
            case Gravity.LEFT:
                return insets.left;
            case Gravity.RIGHT:
                return insets.right;
            case Gravity.BOTTOM:
                return insets.bottom;
            case Gravity.TOP:
            default:
                return insets.top;
        }
    }

    private static boolean isSideGravity(int gravity) {
        return gravity == Gravity.LEFT || gravity == Gravity.RIGHT;
    }

    private void calculateStatusBarLocationsForAllRotations() {
        Rect[] bounds = new Rect[4];
        final DisplayCutout displayCutout = mContext.getDisplay().getCutout();
        bounds[0] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_NONE, displayCutout);
        bounds[1] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_LANDSCAPE, displayCutout);
        bounds[2] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_UPSIDE_DOWN, displayCutout);
        bounds[3] = mContentInsetsProvider
                .getBoundingRectForPrivacyChipForRotation(ROTATION_SEASCAPE, displayCutout);

        try {
            mIWindowManager.updateStaticPrivacyIndicatorBounds(mContext.getDisplayId(), bounds);
        } catch (RemoteException e) {
             //Swallow
        }
    }

    @Override
    public void setForceStatusBarVisible(boolean forceStatusBarVisible, String source) {
        mLogBuffer.log(
                /* tag= */ source,
                LogLevel.DEBUG,
                msg -> {
                    msg.setBool1(forceStatusBarVisible);
                    return kotlin.Unit.INSTANCE;
                },
                msg -> "ForcedVisible set to " + msg.getBool1()
        );
        mCurrentState.mForceStatusBarVisible = forceStatusBarVisible;
        apply(mCurrentState);
    }

    @Override
    public void setOngoingProcessRequiresStatusBarVisible(boolean visible, String source) {
        mLogBuffer.log(
                /* tag= */ source,
                LogLevel.DEBUG,
                msg -> {
                    msg.setBool1(visible);
                    return kotlin.Unit.INSTANCE;
                },
                msg -> "OngoingProcessRequiresVisible set to " + msg.getBool1()
        );
        mCurrentState.mOngoingProcessRequiresStatusBarVisible = visible;
        apply(mCurrentState);
    }

    /**
     * Set whether a launch animation is currently running. If true, this will ensure that the
     * window matches its parent height so that the animation is not clipped by the normal status
     * bar height.
     */
    private void setLaunchAnimationRunning(boolean isLaunchAnimationRunning, String source) {
        if (isLaunchAnimationRunning == mCurrentState.mIsLaunchAnimationRunning) {
            return;
        }

        mLogBuffer.log(
                /* tag= */ source,
                LogLevel.DEBUG,
                msg -> {
                    msg.setBool1(isLaunchAnimationRunning);
                    return kotlin.Unit.INSTANCE;
                },
                msg -> "LaunchAnimRunning set to " + msg.getBool1()
        );
        mCurrentState.mIsLaunchAnimationRunning = isLaunchAnimationRunning;
        apply(mCurrentState);
    }

    private void applyHeight(State state) {
        final int currentRotation = mContext.getDisplay().getRotation();
        rebuildPerRotationParams();
        applyBarSize(mLpChanged, currentRotation, state);
        for (int rot = Surface.ROTATION_0; rot <= Surface.ROTATION_270; rot++) {
            final WindowManager.LayoutParams lp = mLpChanged.paramsForRotation[rot];
            applyBarSize(lp, rot, state);
            // The status bar size could change at runtime if one display has a cutout while
            // another doesn't (like some foldables). It could also change when using debug cutouts.
            // Re-apply the actual content insets each time to avoid bugs like b/290300359.
            final InsetsFrameProvider[] providers = lp.providedInsets;
            if (providers != null) {
                final int thickness = getBarThicknessForRotation(
                        lp.gravity, getDisplayCutoutForRotation(rot));
                for (InsetsFrameProvider provider : providers) {
                    provider.setInsetsSize(getInsets(thickness, lp.gravity));
                }
            }
        }
    }

    private void applyBarSize(
            WindowManager.LayoutParams lp, int rotation, State state) {
        if (state.mIsLaunchAnimationRunning) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            return;
        }
        final int thickness = getBarThicknessForRotation(
                lp.gravity, getDisplayCutoutForRotation(rotation));
        if (isSideGravity(lp.gravity)) {
            lp.width = thickness;
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = thickness;
        }
    }

    /**
     * Get the insets occupied by the status-bar content in its current gravity.
     *
     * The status bar window can temporarily be full-screen during launch animations (see
     * {@link #applyHeight(State)}). Its insets must still describe only the actual content,
     * otherwise apps cannot draw and window-manager animations can be cancelled (b/283958440).
     */
    private static Insets getInsets(int thickness, int gravity) {
        return switch (gravity) {
            case Gravity.LEFT -> Insets.of(thickness, 0, 0, 0);
            case Gravity.RIGHT -> Insets.of(0, 0, thickness, 0);
            case Gravity.BOTTOM -> Insets.of(0, 0, 0, thickness);
            case Gravity.TOP -> Insets.of(0, thickness, 0, 0);
            default -> Insets.NONE;
        };
    }

    private void apply(State state) {
        if (!mIsAttached) {
            return;
        }
        applyForceStatusBarVisibleFlag(state);
        applyHeight(state);
        mLpChanged.gravity = getBarGravityForRotation(mContext.getDisplay().getRotation());
        mStatusBarWindowView.setStatusBarGravity(mLpChanged.gravity);
        if (mLp != null && mLp.copyFrom(mLpChanged) != 0) {
            mWindowManager.updateViewLayout(mStatusBarWindowView, mLp);
        }
    }

    private static class State {
        boolean mForceStatusBarVisible;
        boolean mIsLaunchAnimationRunning;
        boolean mOngoingProcessRequiresStatusBarVisible;
    }

    private void applyForceStatusBarVisibleFlag(State state) {
        mLogBuffer.log(
                /* tag= */ TAG,
                LogLevel.DEBUG,
                msg -> {
                    msg.setInt1(mDisplayId);
                    msg.setBool1(state.mForceStatusBarVisible);
                    msg.setBool2(state.mIsLaunchAnimationRunning);
                    msg.setBool3(state.mOngoingProcessRequiresStatusBarVisible);
                    return kotlin.Unit.INSTANCE;
                },
                msg ->
                        "Status bar window state(display=" + msg.getInt1() + "). "
                                + " ForcedVisible=" + msg.getBool1()
                                + " LaunchAnimRunning=" + msg.getBool2()
                                + " OngoingProcessRequiresVisible=" + msg.getBool3()
        );
        if (state.mForceStatusBarVisible
                || state.mIsLaunchAnimationRunning
                || state.mOngoingProcessRequiresStatusBarVisible) {
            mLpChanged.forciblyShownTypes |= WindowInsets.Type.statusBars();
        } else {
            mLpChanged.forciblyShownTypes &= ~WindowInsets.Type.statusBars();
        }
    }

    @AssistedFactory
    public interface Factory extends StatusBarWindowController.Factory {
        /** Creates a new instance. */
        @NonNull
        @Override
        StatusBarWindowControllerImpl create(
                @NonNull Context context,
                @NonNull WindowManager windowManager,
                @NonNull StatusBarConfigurationController statusBarConfigurationController,
                @NonNull StatusBarContentInsetsProvider contentInsetsProvider,
                int displayId);
    }

}
