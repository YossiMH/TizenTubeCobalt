package dev.yossi.onnkeyguard;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Its manifest requires android.permission.DUMP; no administrative command is exposed over HTTP. */
public final class PolicyAdminReceiver extends BroadcastReceiver {
    public static final String ACTION = "dev.yossi.onnkeyguard.POLICY_ADMIN";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        try {
            setResultData(PolicyRuntime.get(context).admin(intent).toString());
            setResultCode(Activity.RESULT_OK);
        } catch (RuntimeException failure) {
            // Never log intent extras or result data: controller_token contains a private credential.
            setResultData("{\"schema\":1,\"ok\":false,\"reason\":\"admin_unavailable\"}");
            setResultCode(Activity.RESULT_CANCELED);
        }
    }
}
