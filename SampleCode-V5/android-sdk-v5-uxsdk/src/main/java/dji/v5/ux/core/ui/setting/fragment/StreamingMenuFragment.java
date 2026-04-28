package dji.v5.ux.core.ui.setting.fragment;

import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import dji.v5.utils.common.ContextUtil;
import dji.v5.utils.common.StringUtils;
import dji.v5.ux.R;
import dji.v5.ux.core.base.EditorCell;
import dji.v5.ux.core.base.SwitcherCell;
import dji.v5.ux.core.ui.setting.ui.MenuFragment;
import dji.v5.ux.core.util.StreamingManager;
import dji.v5.utils.common.DjiSharedPreferencesManager;

/**
 * Fragment for configuring UDP Video and Telemetry streaming.
 */
public class StreamingMenuFragment extends MenuFragment {

    private static final String VIDEO_PORT_KEY = "streaming_video_port";
    private static final String TELEMETRY_PORT_KEY = "streaming_telemetry_port";
    private static final String TARGET_IP_KEY = "streaming_target_ip";

    @Override
    protected String getPreferencesTitle() {
        return StringUtils.getResStr(ContextUtil.getContext(), R.string.streaming_tab_title);
    }

    @Override
    protected int getLayoutId() {
        return R.layout.uxsdk_setting_menu_streaming_layout;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        EditorCell ipCell = view.findViewById(R.id.setting_streaming_ip);
        EditorCell videoPortCell = view.findViewById(R.id.setting_streaming_video_port);
        SwitcherCell videoSwitch = view.findViewById(R.id.setting_streaming_video_switch);
        
        EditorCell telemetryPortCell = view.findViewById(R.id.setting_streaming_telemetry_port);
        SwitcherCell telemetrySwitch = view.findViewById(R.id.setting_streaming_telemetry_switch);

        // Set Labels manually
        if (ipCell != null) {
            TextView summary = ipCell.findViewById(R.id.summary);
            if (summary != null) {
                summary.setVisibility(View.VISIBLE);
                summary.setText(R.string.streaming_target_ip);
            }
            ipCell.getEditText().setHint("e.g. 255.255.255.255");
            ipCell.getEditText().setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        }
        
        if (videoPortCell != null) {
             TextView summary = videoPortCell.findViewById(R.id.summary);
             if (summary != null) {
                 summary.setVisibility(View.VISIBLE);
                 summary.setText(R.string.streaming_video_udp_port);
             }
        }

        if (videoSwitch != null) {
            videoSwitch.updateSummaryText(getString(R.string.streaming_enable_video));
        }
        
        if (telemetryPortCell != null) {
            TextView summary = telemetryPortCell.findViewById(R.id.summary);
            if (summary != null) {
                summary.setVisibility(View.VISIBLE);
                summary.setText(R.string.streaming_telemetry_udp_port);
            }
        }

        if (telemetrySwitch != null) {
            telemetrySwitch.updateSummaryText(getString(R.string.streaming_enable_telemetry));
        }

        // Load saved settings
        String savedIp = DjiSharedPreferencesManager.getString(getContext(), TARGET_IP_KEY, "255.255.255.255");
        String savedVideoPort = DjiSharedPreferencesManager.getString(getContext(), VIDEO_PORT_KEY, "15560");
        String savedTelemetryPort = DjiSharedPreferencesManager.getString(getContext(), TELEMETRY_PORT_KEY, "14550");
        
        if (ipCell != null) ipCell.getEditText().setText(savedIp);
        if (videoPortCell != null) videoPortCell.setValue(Integer.parseInt(savedVideoPort));
        if (telemetryPortCell != null) telemetryPortCell.setValue(Integer.parseInt(savedTelemetryPort));

        if (videoSwitch != null) {
            videoSwitch.setOnCheckedChangedListener((cell, isChecked) -> {
                if (isChecked) {
                    String ip = ipCell.getEditText().getText().toString();
                    int port = videoPortCell.getValue();
                    DjiSharedPreferencesManager.putString(getContext(), TARGET_IP_KEY, ip);
                    DjiSharedPreferencesManager.putString(getContext(), VIDEO_PORT_KEY, String.valueOf(port));
                    StreamingManager.INSTANCE.startVideoStreaming(port, ip);
                } else {
                    StreamingManager.INSTANCE.stopVideoStreaming();
                }
            });
        }

        if (telemetrySwitch != null) {
            telemetrySwitch.setOnCheckedChangedListener((cell, isChecked) -> {
                if (isChecked) {
                    String ip = ipCell.getEditText().getText().toString();
                    int port = telemetryPortCell.getValue();
                    DjiSharedPreferencesManager.putString(getContext(), TARGET_IP_KEY, ip);
                    DjiSharedPreferencesManager.putString(getContext(), TELEMETRY_PORT_KEY, String.valueOf(port));
                    StreamingManager.INSTANCE.startTelemetryStreaming(port, ip);
                } else {
                    StreamingManager.INSTANCE.stopTelemetryStreaming();
                }
            });
        }
    }
}
