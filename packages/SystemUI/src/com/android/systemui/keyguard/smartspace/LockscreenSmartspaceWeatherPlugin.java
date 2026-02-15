package com.android.systemui.keyguard.smartspace;

import android.content.Context;

import com.android.systemui.shared.R;
import com.android.systemui.smartspace.plugin.BaseSmartspaceDataPlugin;

import javax.inject.Inject;

/**
 * Weather plugin that provides placeholder views to satisfy lockscreen smartspace constraints.
 */
public class LockscreenSmartspaceWeatherPlugin extends BaseSmartspaceDataPlugin {
    @Inject
    public LockscreenSmartspaceWeatherPlugin() {}

    @Override
    public SmartspaceView getView(Context context) {
        LockscreenSmartspacePlaceholderView v = new LockscreenSmartspacePlaceholderView(context);
        // ID comes from com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection.
        v.setId(R.id.weather_smartspace_view);
        return v;
    }

    @Override
    public SmartspaceView getLargeClockView(Context context) {
        LockscreenSmartspacePlaceholderView v = new LockscreenSmartspacePlaceholderView(context);
        // ID comes from com.android.systemui.keyguard.ui.view.layout.sections.SmartspaceSection.
        v.setId(R.id.weather_smartspace_view_large);
        return v;
    }
}
