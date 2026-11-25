package com.pulsecheck.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.telephony.CellIdentityLte;
import android.telephony.CellIdentityNr;
import android.telephony.CellInfo;
import android.telephony.CellInfoGsm;
import android.telephony.CellInfoLte;
import android.telephony.CellInfoNr;
import android.telephony.CellInfoWcdma;
import android.telephony.CellSignalStrengthLte;
import android.telephony.CellSignalStrengthNr;
import android.telephony.TelephonyManager;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.List;

@CapacitorPlugin(
    name = "Radio",
    permissions = {
        @Permission(alias = "location", strings = {Manifest.permission.ACCESS_FINE_LOCATION}),
        @Permission(alias = "phone", strings = {Manifest.permission.READ_PHONE_STATE})
    }
)
public class RadioPlugin extends Plugin {

    private boolean granted() {
        return getPermissionState("location") == PermissionState.GRANTED
            && getPermissionState("phone") == PermissionState.GRANTED;
    }

    @PluginMethod
    public void ensurePermissions(PluginCall call) {
        if (granted()) {
            JSObject r = new JSObject();
            r.put("granted", true);
            call.resolve(r);
        } else {
            requestAllPermissions(call, "permsDone");
        }
    }

    @PermissionCallback
    private void permsDone(PluginCall call) {
        JSObject r = new JSObject();
        r.put("granted", granted());
        call.resolve(r);
    }

    @SuppressLint("MissingPermission")
    @PluginMethod
    public void snapshot(final PluginCall call) {
        if (!granted()) {
            call.reject("permission");
            return;
        }
        final TelephonyManager tm = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        if (tm == null) {
            call.reject("no telephony");
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                tm.requestCellInfoUpdate(getContext().getMainExecutor(), new TelephonyManager.CellInfoCallback() {
                    @Override
                    public void onCellInfo(List<CellInfo> cells) {
                        finish(call, tm, cells);
                    }

                    @Override
                    public void onError(int errorCode, Throwable detail) {
                        finish(call, tm, safeAll(tm));
                    }
                });
            } else {
                finish(call, tm, safeAll(tm));
            }
        } catch (Exception e) {
            finish(call, tm, safeAll(tm));
        }
    }

    @SuppressLint("MissingPermission")
    private List<CellInfo> safeAll(TelephonyManager tm) {
        try {
            return tm.getAllCellInfo();
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressLint("MissingPermission")
    private void finish(PluginCall call, TelephonyManager tm, List<CellInfo> cells) {
        JSObject out = new JSObject();
        try {
            out.put("operator", tm.getNetworkOperatorName());
            out.put("mccmnc", tm.getNetworkOperator());
            out.put("roaming", tm.isNetworkRoaming());
            int nt = Build.VERSION.SDK_INT >= 24 ? tm.getDataNetworkType() : tm.getNetworkType();
            out.put("netType", netName(nt));
        } catch (Exception e) {
            // keep going with whatever we have
        }
        JSArray arr = new JSArray();
        if (cells != null) {
            for (CellInfo ci : cells) {
                try {
                    JSObject c = describe(ci);
                    if (c != null) arr.put(c);
                } catch (Exception e) {
                    // skip a cell we cannot read
                }
            }
        }
        out.put("cells", arr);
        call.resolve(out);
    }

    private JSObject describe(CellInfo ci) {
        JSObject c = new JSObject();
        c.put("reg", ci.isRegistered());
        if (ci instanceof CellInfoLte) {
            CellInfoLte l = (CellInfoLte) ci;
            CellIdentityLte id = l.getCellIdentity();
            CellSignalStrengthLte s = l.getCellSignalStrength();
            c.put("rat", "LTE");
            putI(c, "id", id.getCi());
            putI(c, "pci", id.getPci());
            putI(c, "tac", id.getTac());
            if (Build.VERSION.SDK_INT >= 24) putI(c, "arfcn", id.getEarfcn());
            if (Build.VERSION.SDK_INT >= 30) c.put("bands", ints(id.getBands()));
            if (Build.VERSION.SDK_INT >= 26) {
                putI(c, "rsrp", s.getRsrp());
                putI(c, "rsrq", s.getRsrq());
                putI(c, "sinr", s.getRssnr());
                putI(c, "ta", s.getTimingAdvance());
            } else {
                putI(c, "rsrp", s.getDbm());
            }
            c.put("level", s.getLevel());
            return c;
        }
        if (Build.VERSION.SDK_INT >= 29 && ci instanceof CellInfoNr) {
            return describeNr((CellInfoNr) ci, c);
        }
        if (ci instanceof CellInfoWcdma) {
            CellInfoWcdma w = (CellInfoWcdma) ci;
            c.put("rat", "WCDMA");
            putI(c, "id", w.getCellIdentity().getCid());
            putI(c, "pci", w.getCellIdentity().getPsc());
            putI(c, "rsrp", w.getCellSignalStrength().getDbm());
            c.put("level", w.getCellSignalStrength().getLevel());
            return c;
        }
        if (ci instanceof CellInfoGsm) {
            CellInfoGsm g = (CellInfoGsm) ci;
            c.put("rat", "GSM");
            putI(c, "id", g.getCellIdentity().getCid());
            putI(c, "rsrp", g.getCellSignalStrength().getDbm());
            c.put("level", g.getCellSignalStrength().getLevel());
            return c;
        }
        return null;
    }

    @RequiresApi(29)
    private JSObject describeNr(CellInfoNr n, JSObject c) {
        CellIdentityNr id = (CellIdentityNr) n.getCellIdentity();
        CellSignalStrengthNr s = (CellSignalStrengthNr) n.getCellSignalStrength();
        c.put("rat", "NR");
        long nci = id.getNci();
        if (nci != Long.MAX_VALUE) c.put("id", nci);
        putI(c, "pci", id.getPci());
        putI(c, "tac", id.getTac());
        putI(c, "arfcn", id.getNrarfcn());
        if (Build.VERSION.SDK_INT >= 30) c.put("bands", ints(id.getBands()));
        putI(c, "rsrp", s.getSsRsrp());
        putI(c, "rsrq", s.getSsRsrq());
        putI(c, "sinr", s.getSsSinr());
        c.put("level", s.getLevel());
        return c;
    }

    private static void putI(JSObject o, String k, int v) {
        if (v != Integer.MAX_VALUE && v != CellInfo.UNAVAILABLE) o.put(k, v);
    }

    private static JSArray ints(int[] a) {
        JSArray r = new JSArray();
        if (a != null) for (int x : a) r.put(x);
        return r;
    }

    private static String netName(int t) {
        switch (t) {
            case 20: return "5G NR";
            case 19: return "4G LTE-A";
            case 13: return "4G LTE";
            case 15: return "3G HSPA+";
            case 8: case 9: case 10: return "3G HSPA";
            case 3: return "3G UMTS";
            case 5: case 6: case 12: case 14: return "3G EVDO";
            case 2: return "2G EDGE";
            case 1: return "2G GPRS";
            case 4: case 7: case 11: return "2G CDMA";
            default: return "Unknown";
        }
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        String kind = call.getString("kind", "wireless");
        Intent i;
        switch (kind) {
            case "airplane": i = new Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS); break;
            case "operator": i = new Intent(Settings.ACTION_NETWORK_OPERATOR_SETTINGS); break;
            case "apn": i = new Intent(Settings.ACTION_APN_SETTINGS); break;
            case "data": i = new Intent("android.settings.DATA_USAGE_SETTINGS"); break;
            case "radioinfo":
                i = new Intent();
                i.setClassName("com.android.settings", "com.android.settings.RadioInfo");
                break;
            default: i = new Intent(Settings.ACTION_WIRELESS_SETTINGS);
        }
        try {
            getActivity().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            try {
                Intent fb = "radioinfo".equals(kind)
                    ? new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "*#*#4636#*#*", null))
                    : new Intent(Settings.ACTION_WIRELESS_SETTINGS);
                getActivity().startActivity(fb);
                call.resolve();
            } catch (Exception e2) {
                call.reject("blocked");
            }
        }
    }

    @PluginMethod
    public void keepAwake(final PluginCall call) {
        final boolean on = call.getBoolean("on", true);
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }
}
