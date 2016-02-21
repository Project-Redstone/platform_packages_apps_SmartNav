/**
 * Copyright (C) 2008 The Android Open Source Project
 * Copyright (C) 2014 The TeamEos Project
 * Copyright (C) 2016 The DirtyUnicorns Project
 * 
 * @author: Randall Rushing <randall.rushing@gmail.com>
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
 * 
 * Base navigation bar abstraction for managing keyguard policy, internal
 * bar behavior, and everything else not feature implementation specific
 * 
 */

package com.android.internal.navigation;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

import com.android.internal.navigation.BarTransitions;
import com.android.internal.navigation.pulse.PulseController;
import com.android.internal.navigation.pulse.PulseController.PulseObserver;
import com.android.internal.navigation.utils.ColorAnimator;
import com.android.internal.navigation.utils.SmartObserver;
import com.android.internal.navigation.utils.SmartObserver.SmartObservable;
import com.android.internal.utils.du.ActionConstants;
import com.android.internal.utils.du.DUActionUtils;
import com.android.internal.utils.du.ImageHelper;
import com.android.internal.utils.du.ActionHandler.ActionIconMap;
import com.facebook.rebound.Spring;
import com.facebook.rebound.SpringSystem;

import android.animation.LayoutTransition;
import android.app.StatusBarManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewRootImpl;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public abstract class BaseNavigationBar extends LinearLayout implements Navigator, PulseObserver {
    final static String TAG = "PhoneStatusBar/BaseNavigationBar";
    public final static boolean DEBUG = false;
    public static final boolean NAVBAR_ALWAYS_AT_RIGHT = true;
    // slippery nav bar when everything is disabled, e.g. during setup
    final static boolean SLIPPERY_WHEN_DISABLED = true;

    // workaround for LayoutTransitions leaving the nav buttons in a weird state (bug 5549288)
    final static boolean WORKAROUND_INVALID_LAYOUT = true;
    final static int MSG_CHECK_INVALID_LAYOUT = 8686;

    public static final int MSG_SET_DISABLED_FLAGS = 101;
    public static final int MSG_INVALIDATE = 102;

    private boolean mKeyguardShowing;

    protected H mHandler = new H();
    protected final Display mDisplay;
    protected View[] mRotatedViews = new View[4];
    protected View mCurrentView = null;
    protected FrameLayout mRot0, mRot90;
    protected int mDisabledFlags = 0;
    protected int mNavigationIconHints = 0;
    protected boolean mVertical;
    protected boolean mScreenOn;
    protected boolean mLeftInLandscape;
    protected boolean mLayoutTransitionsEnabled;
    protected boolean mWakeAndUnlocking;
    protected boolean mScreenPinningEnabled;
    protected final boolean mIsTablet;
    protected OnVerticalChangedListener mOnVerticalChangedListener;
    protected SmartObserver mSmartObserver;
    protected PulseController mPulse;
    protected ActionIconMap mIconMap;

    // use access methods to keep state proper
    private SpringSystem mSpringSystem;

    // listeners from PhoneStatusBar
    protected View.OnTouchListener mHomeActionListener;
    protected View.OnTouchListener mUserAutoHideListener;

    private class H extends Handler {
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_INVALID_LAYOUT:
                    final String how = "" + m.obj;
                    final int w = getWidth();
                    final int h = getHeight();
                    final int vw = mCurrentView.getWidth();
                    final int vh = mCurrentView.getHeight();

                    if (h != vh || w != vw) {
                        Log.w(TAG, String.format(
                            "*** Invalid layout in navigation bar (%s this=%dx%d cur=%dx%d)",
                            how, w, h, vw, vh));
                        if (WORKAROUND_INVALID_LAYOUT) {
                            requestLayout();
                        }
                    }
                    break;
                case MSG_SET_DISABLED_FLAGS:
                    setDisabledFlags(mDisabledFlags, true);
                    break;
                case MSG_INVALIDATE:
                    invalidate();
                    break;
            }
        }
    }

    public BaseNavigationBar(Context context) {
        this(context, null);
    }

    public BaseNavigationBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDisplay = ((WindowManager) context.getSystemService(
                Context.WINDOW_SERVICE)).getDefaultDisplay();
        mSmartObserver = new SmartObserver(mHandler, context.getContentResolver());
        mSpringSystem = SpringSystem.create();
        mIsTablet = !DUActionUtils.isNormalScreen();
        mVertical = false;
    }

    // require implementation. Surely they have something to clean up
    protected abstract void onDispose();

    // any implementation specific handling can be handled here
    protected void onInflateFromUser() {}

    protected void onKeyguardShowing(boolean showing){}

    public void abortCurrentGesture(){}

    public void setMenuVisibility(final boolean show) {}
    public void setMenuVisibility(final boolean show, final boolean force) {}
    public void setNavigationIconHints(int hints) {}
    public void setNavigationIconHints(int hints, boolean force) {}
    public void onHandlePackageChanged(){}

    public View getRecentsButton() { return null; }
    public View getMenuButton() { return null; }
    public View getBackButton() { return null; }
    public View getHomeButton() { return null; }
    public boolean isInEditMode() { return false; }

    public void onRecreateStatusbar() {}

    public void setIconMap(ActionIconMap iconMap) {
        mIconMap = iconMap;
    }

    public void updateNavbarThemedResources(Resources res){
        getBarTransitions().updateResources(res);
    }

    @Override
    public void screenPinningStateChanged(boolean enabled) {
        mScreenPinningEnabled = enabled;
    }

	@Override
	public void setListeners(OnTouchListener homeActionListener,
			OnLongClickListener homeLongClickListener,
			OnTouchListener userAutoHideListener,
			OnClickListener recentsClickListener,
			OnTouchListener recentsTouchListener,
			OnLongClickListener recentsLongClickListener) {
	    mUserAutoHideListener = userAutoHideListener;
	}

	@Override
	public void setControllers(PulseController pulseController) {
	    mPulse = pulseController;
	    mPulse.setPulseObserver(this);
	}

	@Override
    public void setWakeAndUnlocking(boolean wakeAndUnlocking) {
        setUseFadingAnimations(wakeAndUnlocking);
        mWakeAndUnlocking = wakeAndUnlocking;
        updateLayoutTransitionsEnabled();
    }

    public SpringSystem getSpringSystem() {
        if (mSpringSystem == null) {
            mSpringSystem = SpringSystem.create();
        }
        return mSpringSystem;
    }

    public void flushSpringSystem() {
        if (mSpringSystem != null) {
            for (Spring spring : mSpringSystem.getAllSprings()) {
                spring.setAtRest();
                spring.removeAllListeners();
                spring.destroy();
            }
            mSpringSystem.removeAllListeners();
            mSpringSystem = null;
        }
    }

    protected boolean areAnyHintsActive() {
        return ((mDisabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0)
                || ((mDisabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0)
                || (((mDisabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0 && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0)));

    }

	protected void setUseFadingAnimations(boolean useFadingAnimations) {
		WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
		if (lp != null) {
			boolean old = lp.windowAnimations != 0;
			if (!old && useFadingAnimations) {
				lp.windowAnimations = DUActionUtils.getIdentifier(mContext,
						"Animation_NavigationBarFadeIn", "style",
						DUActionUtils.PACKAGE_SYSTEMUI);
			} else if (old && !useFadingAnimations) {
				lp.windowAnimations = 0;
			} else {
				return;
			}
			WindowManager wm = (WindowManager) getContext().getSystemService(
					Context.WINDOW_SERVICE);
			wm.updateViewLayout(this, lp);
		}
	}

    protected void updateLayoutTransitionsEnabled() {
        boolean enabled = !mWakeAndUnlocking && mLayoutTransitionsEnabled;
        ViewGroup navButtons = (ViewGroup) mCurrentView.findViewById(findViewByIdName("nav_buttons"));
        if (navButtons == null) {
            navButtons = (ViewGroup) mCurrentView.findViewWithTag(Res.Common.NAV_BUTTONS);
        }
        LayoutTransition lt = navButtons.getLayoutTransition();
        if (lt != null) {
            if (enabled) {
                lt.enableTransitionType(LayoutTransition.APPEARING);
                lt.enableTransitionType(LayoutTransition.DISAPPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.enableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            } else {
                lt.disableTransitionType(LayoutTransition.APPEARING);
                lt.disableTransitionType(LayoutTransition.DISAPPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
                lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
            }
        }
    }

    @Override
    public void setLayoutTransitionsEnabled(boolean enabled) {
        mLayoutTransitionsEnabled = enabled;
        updateLayoutTransitionsEnabled();
    }

    public int findViewByIdName(String name) {
        return DUActionUtils.getId(getContext(), name,
                DUActionUtils.PACKAGE_SYSTEMUI);
    }

    public void setForgroundColor(Drawable drawable) {
        if (mRot0 != null) {
            mRot0.setForeground(drawable);
        }
        if (mRot90 != null) {
            mRot90.setForeground(drawable);
        }
    }

    public void setLeftInLandscape(boolean leftInLandscape) {
        if (mLeftInLandscape != leftInLandscape) {
            mLeftInLandscape = leftInLandscape;
            if (mPulse != null) {
                mPulse.setLeftInLandscape(leftInLandscape);
            }
        }
    }

    // keep keyguard methods final and use getter to access
    public final void setKeyguardShowing(boolean showing) {
        if (mKeyguardShowing != showing) {
            mKeyguardShowing = showing;
            if (mPulse != null) {
                mPulse.setKeyguardShowing(showing);
            }
            onKeyguardShowing(showing);
        }
    }

    public final boolean isKeyguardShowing() {
        return mKeyguardShowing;
    }

    // if a bar instance is created from a user mode switch
    // PhoneStatusBar should call this. This allows the view
    // to make adjustments that are otherwise not needed when
    // inflating on boot, such as setting proper transition flags
    public final void notifyInflateFromUser() {
        getBarTransitions().transitionTo(BarTransitions.MODE_TRANSPARENT, false);
        ContentResolver resolver = getContext().getContentResolver();

        // PhoneStatusBar doesn't set this when user inflates a bar, only when
        // actual value changes #common_cm
        mLeftInLandscape = Settings.System.getIntForUser(resolver,
                Settings.System.NAVBAR_LEFT_IN_LANDSCAPE, 0, UserHandle.USER_CURRENT) == 1;
        // we boot with screen off, but we need to force it true here
        mScreenOn = true;
        if (mPulse != null) {
            mPulse.notifyScreenOn(mScreenOn);
        }
        onInflateFromUser();
    }

    public void setTransparencyAllowedWhenVertical(boolean allowed) {
//        getBarTransitions().setTransparencyAllowedWhenVertical(allowed);
    }

    public View getCurrentView() {
        return mCurrentView;
    }

    public View getHiddenView() {
        if (mCurrentView.equals(mRot0)) {
            return mRot90;
        } else {
            return mRot0;
        }
    }

    public View.OnTouchListener getHomeActionListener() {
        return mHomeActionListener;
    }

    public void setDisabledFlags(int disabledFlags) {
        setDisabledFlags(disabledFlags, false);
    }

    public boolean isVertical() {
        return mVertical;
    }

    public final void setOnVerticalChangedListener(OnVerticalChangedListener onVerticalChangedListener) {
        mOnVerticalChangedListener = onVerticalChangedListener;
        notifyVerticalChangedListener(mVertical);
    }

    public final void dispose() {
        mSmartObserver.cleanUp();
        if (mPulse != null) {
            mPulse.removePulseObserver();
        }
        flushSpringSystem();
        onDispose();
    }

    private void notifyVerticalChangedListener(boolean newVertical) {
        if (mOnVerticalChangedListener != null) {
            mOnVerticalChangedListener.onVerticalChanged(mVertical);
        }
    }

    public void notifyScreenOn(boolean screenOn) {
        mScreenOn = screenOn;
        if (mPulse != null) {
            mPulse.notifyScreenOn(screenOn);
        }
        setDisabledFlags(mDisabledFlags, true);
    }

    public void setSlippery(boolean newSlippery) {
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) getLayoutParams();
        if (lp != null) {
            boolean oldSlippery = (lp.flags & WindowManager.LayoutParams.FLAG_SLIPPERY) != 0;
            if (!oldSlippery && newSlippery) {
                lp.flags |= WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else if (oldSlippery && !newSlippery) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_SLIPPERY;
            } else {
                return;
            }
            WindowManager wm = (WindowManager)getContext().getSystemService(Context.WINDOW_SERVICE);
            wm.updateViewLayout(this, lp);
        }
    }

    public void reorient() {
        final int rot = mDisplay.getRotation();
        for (int i=0; i<4; i++) {
            mRotatedViews[i].setVisibility(View.GONE);
        }
        mCurrentView = mRotatedViews[rot];
        mCurrentView.setVisibility(View.VISIBLE);

        if (DEBUG) {
            Log.d(TAG, "reorient(): rot=" + mDisplay.getRotation());
        }      
    }

    @Override
    public void onFinishInflate() {
        int rot0id = DUActionUtils.getId(getContext(), "rot0", DUActionUtils.PACKAGE_SYSTEMUI);
        int rot90id = DUActionUtils.getId(getContext(), "rot90", DUActionUtils.PACKAGE_SYSTEMUI);

        mRot0 = (FrameLayout) findViewById(rot0id);
        mRot90 = (FrameLayout) findViewById(rot90id);
        mRotatedViews[Surface.ROTATION_0] =
        mRotatedViews[Surface.ROTATION_180] = mRot0;
        mRotatedViews[Surface.ROTATION_90] = mRot90;
        mRotatedViews[Surface.ROTATION_270] = mRotatedViews[Surface.ROTATION_90];
        mCurrentView = mRotatedViews[Surface.ROTATION_0];
    }

    public void setDisabledFlags(int disabledFlags, boolean force) {
        if (!force && mDisabledFlags == disabledFlags)
            return;
        mDisabledFlags = disabledFlags;

        final boolean disableHome = ((disabledFlags & View.STATUS_BAR_DISABLE_HOME) != 0);
        final boolean disableRecent = ((disabledFlags & View.STATUS_BAR_DISABLE_RECENT) != 0);
        final boolean disableBack = ((disabledFlags & View.STATUS_BAR_DISABLE_BACK) != 0)
                && ((mNavigationIconHints & StatusBarManager.NAVIGATION_HINT_BACK_ALT) == 0);
        final boolean disableSearch = ((disabledFlags & View.STATUS_BAR_DISABLE_SEARCH) != 0);

        if (SLIPPERY_WHEN_DISABLED) {
            setSlippery(disableHome && disableRecent && disableBack && disableSearch);
        }
    }

    @Override
    public final View getBaseView() {
        return (View)this;
    }

    // for when we don't inflate xml
    protected void createBaseViews() {
        LinearLayout rot0NavButton = new LinearLayout(getContext());
        rot0NavButton.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        rot0NavButton.setOrientation(LinearLayout.HORIZONTAL);
        rot0NavButton.setClipChildren(false);
        rot0NavButton.setClipToPadding(false);
        rot0NavButton.setLayoutTransition(new LayoutTransition());
        rot0NavButton.setTag(Res.Common.NAV_BUTTONS);

        LinearLayout rot90NavButton = new LinearLayout(getContext());
        rot90NavButton.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        rot90NavButton.setOrientation(mIsTablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        rot90NavButton.setClipChildren(false);
        rot90NavButton.setClipToPadding(false);
        rot90NavButton.setLayoutTransition(new LayoutTransition());
        rot90NavButton.setTag(Res.Common.NAV_BUTTONS);

        LinearLayout rot0LightsOut = new LinearLayout(getContext());
        rot0LightsOut.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        rot0LightsOut.setOrientation(LinearLayout.HORIZONTAL);
        rot0LightsOut.setVisibility(View.GONE);
        rot0LightsOut.setTag(Res.Common.LIGHTS_OUT);

        LinearLayout rot90LightsOut = new LinearLayout(getContext());
        rot90LightsOut.setLayoutParams(new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        rot90LightsOut.setOrientation(mIsTablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        rot0LightsOut.setVisibility(View.GONE);
        rot90LightsOut.setTag(Res.Common.LIGHTS_OUT);

        mRot0 = new FrameLayout(getContext());
        mRot0.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));

        mRot90 = new FrameLayout(getContext());
        mRot90.setLayoutParams(new LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT));
        mRot90.setVisibility(View.GONE);
        mRot90.setPadding(mRot90.getPaddingLeft(), 0, mRot90.getPaddingRight(),
                mRot90.getPaddingBottom());

        if (!BarTransitions.HIGH_END) {
            setBackground(DUActionUtils.getDrawable(getContext(), Res.Common.SYSTEM_BAR_BACKGROUND,
                    DUActionUtils.PACKAGE_SYSTEMUI));
        }

        mRot0.addView(rot0NavButton);
        mRot0.addView(rot0LightsOut);

        mRot90.addView(rot90NavButton);
        mRot90.addView(rot90LightsOut);

        addView(mRot0);
        addView(mRot90);

        mRotatedViews[Surface.ROTATION_0] =
                mRotatedViews[Surface.ROTATION_180] = mRot0;
        mRotatedViews[Surface.ROTATION_90] = mRot90;
        mRotatedViews[Surface.ROTATION_270] = mRotatedViews[Surface.ROTATION_90];
        mCurrentView = mRotatedViews[Surface.ROTATION_0];
    }

    protected void setVisibleOrGone(View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? VISIBLE : GONE);
        }
    }

    private void postCheckForInvalidLayout(final String how) {
        mHandler.obtainMessage(MSG_CHECK_INVALID_LAYOUT, 0, 0, how).sendToTarget();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewRootImpl root = getViewRootImpl();
        if (root != null) {
            root.setDrawDuringWindowsAnimating(true);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (DEBUG) Log.d(TAG, String.format(
                    "onSizeChanged: (%dx%d) old: (%dx%d)", w, h, oldw, oldh));

        final boolean newVertical = w > 0 && h > w;
        if (newVertical != mVertical) {
            mVertical = newVertical;
            //Log.v(TAG, String.format("onSizeChanged: h=%d, w=%d, vert=%s", h, w, mVertical?"y":"n"));
            reorient();
            notifyVerticalChangedListener(newVertical);
        }

        postCheckForInvalidLayout("sizeChanged");
        super.onSizeChanged(w, h, oldw, oldh);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (mPulse != null) {
            mPulse.onDraw(canvas);
        }
    }

    @Override
    public Handler getHandler() {
        return mHandler;
    }

    protected String getResourceName(int resId) {
        if (resId != 0) {
            final android.content.res.Resources res = getContext().getResources();
            try {
                return res.getResourceName(resId);
            } catch (android.content.res.Resources.NotFoundException ex) {
                return "(unknown)";
            }
        } else {
            return "(null)";
        }
    }

    protected static String visibilityToString(int vis) {
        switch (vis) {
            case View.INVISIBLE:
                return "INVISIBLE";
            case View.GONE:
                return "GONE";
            default:
                return "VISIBLE";
        }
    }

    public Drawable getLocalDrawable(String resName, Resources res) {
        int id = getDrawableId(resName);
        Drawable icon = ImageHelper.getVector(res, id, false);
        if (icon == null) {
            icon = res.getDrawable(id);
        }
        return icon;
    }

    public int getDrawableId(String resName) {
        try {
            int ident = getContext().getResources().getIdentifier(resName, "drawable",
                    getContext().getPackageName());
            return ident;
        } catch (Exception e) {
            return -1;
        }
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("NavigationBarView {");
        final Rect r = new Rect();
        final Point size = new Point();
        mDisplay.getRealSize(size);

        pw.println(String.format("      this: " + viewInfo(this)
                        + " " + visibilityToString(getVisibility())));

        getWindowVisibleDisplayFrame(r);
        final boolean offscreen = r.right > size.x || r.bottom > size.y;
        pw.println("      window: "
                + r.toShortString()
                + " " + visibilityToString(getWindowVisibility())
                + (offscreen ? " OFFSCREEN!" : ""));

        pw.println(String.format("      mCurrentView: id=%s (%dx%d) %s",
                        getResourceName(mCurrentView.getId()),
                        mCurrentView.getWidth(), mCurrentView.getHeight(),
                        visibilityToString(mCurrentView.getVisibility())));

        pw.println("    }");
    }

    protected static String viewInfo(View v) {
        return "[(" + v.getLeft() + "," + v.getTop() + ")(" + v.getRight() + "," + v.getBottom()
                + ") " + v.getWidth() + "x" + v.getHeight() + "]";
    }
}
