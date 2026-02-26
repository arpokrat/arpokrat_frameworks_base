package com.android.systemui.keyguard.smartspace;

import android.annotation.StyleRes;
import android.app.smartspace.SmartspaceTarget;
import android.app.smartspace.uitemplatedata.BaseTemplateData;
import android.app.smartspace.uitemplatedata.CombinedCardsTemplateData;
import android.app.smartspace.uitemplatedata.Icon;
import android.app.smartspace.uitemplatedata.Text;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.graphics.ColorUtils;
import com.android.settingslib.Utils;
import com.android.systemui.res.R;
import com.android.systemui.smartspace.ui.DefaultSmartspaceView;

import java.util.List;

/**
 * Smartspace view for displaying date, zen mode, upcoming alarms and media. This is a
 * reimplementation of the legacy KeyguardSliceView.
 */
public class LockscreenSmartspaceGeneralView extends LinearLayout implements DefaultSmartspaceView {

    private static final int DARK_AMOUNT = 0;

    private LockscreenSmartspaceGeneralView.Row mRow;
    private int mTextColor;
    private int mIconSize;


    public LockscreenSmartspaceGeneralView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mRow = findViewById(R.id.row);
        mTextColor = Utils.getColorAttrDefaultColor(mContext, R.attr.wallpaperTextColor);
        mIconSize = (int) mContext.getResources().getDimension(R.dimen.widget_icon_size);
    }

    protected void showTarget(SmartspaceTarget target) {
        // Remove old views.
        // TODO: Improve efficiency by reusing existing views and only removing those which are no
        // longer needed.
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            mRow.removeView(child);
            i--;
        }

        CombinedCardsTemplateData template = (CombinedCardsTemplateData)target.getTemplateData();
        List<BaseTemplateData> subTemplates = template.getCombinedCardDataList();

        mRow.setVisibility(!subTemplates.isEmpty() ? VISIBLE : GONE);
        LinearLayout.LayoutParams layoutParams = (LayoutParams) mRow.getLayoutParams();
        layoutParams.gravity = Gravity.START;
        mRow.setLayoutParams(layoutParams);

        for (BaseTemplateData data : subTemplates) {
            BaseTemplateData.SubItemInfo primaryItem = data.getPrimaryItem();
            BaseTemplateData.SubItemInfo subTitleItem = data.getSubtitleItem();
            if (primaryItem != null) {
                drawLine(primaryItem);
                if (subTitleItem != null) {
                    drawLine(subTitleItem);
                }
            }
        }
    }

    private void drawLine(BaseTemplateData.SubItemInfo subItemInfo) {
        LockscreenSmartspaceGeneralTextView tv = new LockscreenSmartspaceGeneralTextView(mContext);
        tv.setTextColor(getTextColor());
        mRow.addView(tv, -1);

        Text text = subItemInfo.getText();
        tv.setText(text == null ? null : text.getText());

        Drawable iconDrawable = null;
        Icon icon = subItemInfo.getIcon();
        if (icon != null) {
            iconDrawable = icon.getIcon().loadDrawable(mContext);
            if (iconDrawable != null) {
                if (iconDrawable instanceof InsetDrawable) {
                    // System icons (DnD) use insets which are fine for centered slice content
                    // but will cause a slight indent for left/right-aligned slice views.
                    iconDrawable = ((InsetDrawable) iconDrawable).getDrawable();
                }
                final int width = (int) (iconDrawable.getIntrinsicWidth()
                        / (float) iconDrawable.getIntrinsicHeight() * mIconSize);
                iconDrawable.setBounds(0, 0, Math.max(width, 1), mIconSize);
            }
        }
        tv.setCompoundDrawablesRelative(iconDrawable, null, null, null);
    }

    private int getTextColor() {
        return ColorUtils.blendARGB(mTextColor, Color.WHITE, DARK_AMOUNT);
    }

    void onDensityOrFontScaleChanged() {
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (child instanceof LockscreenSmartspaceGeneralTextView) {
                ((LockscreenSmartspaceGeneralTextView) child).onDensityOrFontScaleChanged();
            }
        }
    }

    void onOverlayChanged() {
        for (int i = 0; i < mRow.getChildCount(); i++) {
            View child = mRow.getChildAt(i);
            if (child instanceof LockscreenSmartspaceGeneralTextView) {
                ((LockscreenSmartspaceGeneralTextView) child).onOverlayChanged();
            }
        }
    }

    public static class Row extends LinearLayout {

        public Row(Context context) {
            this(context, null);
        }

        public Row(Context context, AttributeSet attrs) {
            this(context, attrs, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr) {
            this(context, attrs, defStyleAttr, 0);
        }

        public Row(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
            super(context, attrs, defStyleAttr, defStyleRes);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int childCount = getChildCount();

            for (int i = 0; i < childCount; i++) {
                View child = getChildAt(i);
                if (child instanceof LockscreenSmartspaceGeneralTextView) {
                    ((LockscreenSmartspaceGeneralTextView) child).setMaxWidth(Integer.MAX_VALUE);
                }
            }

            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

    public static class LockscreenSmartspaceGeneralTextView extends TextView {

        @StyleRes
        private static final int sStyleId = R.style.TextAppearance_Keyguard_Secondary;

        LockscreenSmartspaceGeneralTextView(Context context) {
            super(context, null /* attrs */, 0 /* styleAttr */, sStyleId);
            onDensityOrFontScaleChanged();
            setEllipsize(TextUtils.TruncateAt.END);
        }

        public void onDensityOrFontScaleChanged() {
            updatePadding();
        }

        public void onOverlayChanged() {
            setTextAppearance(sStyleId);
        }

        @Override
        public void setText(CharSequence text, BufferType type) {
            super.setText(text, type);
            updatePadding();
        }

        private void updatePadding() {
            boolean hasText = !TextUtils.isEmpty(getText());
            int padding = (int) getContext().getResources()
                    .getDimension(R.dimen.widget_horizontal_padding) / 2;
            // Orientation is vertical, so add padding to top and bottom.
            setPadding(0, padding, 0, hasText ? padding : 0);

            setCompoundDrawablePadding((int) mContext.getResources()
                    .getDimension(R.dimen.widget_icon_padding));
        }

        @Override
        public void setTextColor(int color) {
            super.setTextColor(color);
            updateDrawableColors();
        }

        @Override
        public void setCompoundDrawablesRelative(Drawable start, Drawable top, Drawable end,
                Drawable bottom) {
            super.setCompoundDrawablesRelative(start, top, end, bottom);
            updateDrawableColors();
            updatePadding();
        }

        private void updateDrawableColors() {
            final int color = getCurrentTextColor();
            for (Drawable drawable : getCompoundDrawables()) {
                if (drawable != null) {
                    drawable.setTint(color);
                }
            }
        }
    }
}
