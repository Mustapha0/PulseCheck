package com.pulsecheck.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rikka.shizuku.Shizuku;

/**
 * Phone controls that need shell rights. Every command runs through Shizuku.
 * JavaScript never sends a command: it can only call the fixed operations below,
 * and the few values it passes (host, preset, slot, seconds) are validated here.
 */
@CapacitorPlugin(name = "Tools")
public class ToolsPlugin extends Plugin {

    private static final String SHIZUKU_PKG = "moe.shizuku.privileged.api";
    private static final int PERM_CODE = 4242;
    private static final long NR_BIT = 1L << 19;          // NETWORK_TYPE_BITMASK_NR
    private static final int MAX_OUT = 400000;
    private static final ExecutorService POOL = Executors.newCachedThreadPool();

    private interface Job {
        void run(PluginCall call) throws Exception;
    }

    private static final class Res {
        volatile int code = -1;
        String out = "";
        String err = "";
        boolean timeout = false;
        String fail = null;

        boolean ok() {
            return fail == null && !timeout && code == 0;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Shizuku state                                                       */
    /* ------------------------------------------------------------------ */

    private boolean installed() {
        try {
            getContext().getPackageManager().getPackageInfo(SHIZUKU_PKG, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean ready() {
        try {
            return Shizuku.pingBinder()
                && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        JSObject r = new JSObject();
        boolean running = false;
        boolean granted = false;
        boolean blocked = false;
        boolean preV11 = false;
        int uid = -1;
        try {
            running = Shizuku.pingBinder();
        } catch (Throwable t) {
            running = false;
        }
        if (running) {
            try {
                preV11 = Shizuku.isPreV11();
            } catch (Throwable t) {
                preV11 = false;
            }
            try {
                granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
                if (!granted) blocked = Shizuku.shouldShowRequestPermissionRationale();
            } catch (Throwable t) {
                granted = false;
            }
            try {
                uid = Shizuku.getUid();
            } catch (Throwable t) {
                uid = -1;
            }
        }
        r.put("installed", installed());
        r.put("running", running);
        r.put("granted", granted);
        r.put("blocked", blocked);
        r.put("preV11", preV11);
        r.put("uid", uid);
        call.resolve(r);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        try {
            if (!Shizuku.pingBinder()) {
                call.reject("not-running");
                return;
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(PERM_CODE);
            }
            call.resolve();
        } catch (Throwable t) {
            call.reject("request-failed");
        }
    }

    @PluginMethod
    public void openShizuku(PluginCall call) {
        try {
            Intent i = getContext().getPackageManager().getLaunchIntentForPackage(SHIZUKU_PKG);
            if (i == null) {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/download/"));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("blocked");
        }
    }

    /* ------------------------------------------------------------------ */
    /* Running commands through Shizuku                                    */
    /* ------------------------------------------------------------------ */

    /** Shizuku.newProcess is private in recent API versions, so call it by reflection. */
    private static Process newProcess(String[] cmd) throws Exception {
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, (Object) cmd, null, null);
        } catch (InvocationTargetException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw e;
        }
    }

    private static Thread reader(final InputStream in, final StringBuilder sb) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    char[] buf = new char[4096];
                    int n;
                    while ((n = br.read(buf)) != -1) {
                        synchronized (sb) {
                            if (sb.length() < MAX_OUT) sb.append(buf, 0, n);
                        }
                    }
                } catch (Exception ignored) {
                    // stream closed
                }
            }
        });
        t.setDaemon(true);
        return t;
    }

    /** Runs a script with sh and waits up to timeoutSec. */
    private Res sh(String script, int timeoutSec) {
        final Res res = new Res();
        final Process p;
        try {
            p = newProcess(new String[]{"sh", "-c", script});
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            res.fail = c.getClass().getSimpleName() + ": " + c.getMessage();
            return res;
        }
        final StringBuilder out = new StringBuilder();
        final StringBuilder err = new StringBuilder();
        Thread to = reader(p.getInputStream(), out);
        Thread te = reader(p.getErrorStream(), err);
        to.start();
        te.start();
        Thread w = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    res.code = p.waitFor();
                } catch (Exception e) {
                    res.fail = "wait failed";
                }
            }
        });
        w.setDaemon(true);
        w.start();
        try {
            w.join(timeoutSec * 1000L);
            if (w.isAlive()) {
                res.timeout = true;
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                    // best effort
                }
                w.join(1500);
            }
            to.join(2000);
            te.join(2000);
        } catch (InterruptedException ignored) {
            // fall through with what we have
        }
        synchronized (out) {
            res.out = out.toString();
        }
        synchronized (err) {
            res.err = err.toString();
        }
        return res;
    }

    private void async(final PluginCall call, final Job job) {
        if (!ready()) {
            call.reject("not-ready");
            return;
        }
        POOL.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    job.run(call);
                } catch (Throwable t) {
                    call.reject(String.valueOf(t.getMessage()));
                }
            }
        });
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static JSObject shellResult(Res r) {
        JSObject o = new JSObject();
        o.put("ok", r.ok());
        o.put("code", r.code);
        o.put("timeout", r.timeout);
        o.put("out", r.out);
        o.put("err", clip(r.err, 2000));
        if (r.fail != null) o.put("fail", r.fail);
        return o;
    }

    private static int clampInt(PluginCall call, String key, int def, int lo, int hi) {
        Integer v = call.getInt(key, def);
        int x = v == null ? def : v;
        return Math.max(lo, Math.min(hi, x));
    }

    private static int slotOf(PluginCall call) {
        return clampInt(call, "slot", 0, 0, 3);
    }

    /* ------------------------------------------------------------------ */
    /* Reconnect: airplane mode and mobile data                            */
    /* ------------------------------------------------------------------ */

    private static final String AIR_FUNCS =
        "air_on() { cmd connectivity airplane-mode enable >/dev/null 2>&1; "
        + "[ \"$(settings get global airplane_mode_on)\" = \"1\" ] || { settings put global airplane_mode_on 1; "
        + "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true >/dev/null 2>&1; }; }\n"
        + "air_off() { cmd connectivity airplane-mode disable >/dev/null 2>&1; "
        + "[ \"$(settings get global airplane_mode_on)\" = \"0\" ] || { settings put global airplane_mode_on 0; "
        + "am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false >/dev/null 2>&1; }; }\n";

    /**
     * The whole cycle runs as one shell script, so it still finishes
     * (and switches airplane mode back off) even if the app is closed halfway.
     */
    @PluginMethod
    public void airplaneCycle(PluginCall call) {
        final int secs = clampInt(call, "seconds", 6, 3, 30);
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                String script = AIR_FUNCS
                    + "air_on; sleep 1; echo \"on=$(settings get global airplane_mode_on)\"\n"
                    + "sleep " + secs + "\n"
                    + "for i in 1 2 3; do air_off; sleep 1; "
                    + "[ \"$(settings get global airplane_mode_on)\" = \"0\" ] && break; done\n"
                    + "echo \"off=$(settings get global airplane_mode_on)\"\n";
                Res r = sh(script, secs + 25);
                JSObject o = shellResult(r);
                boolean wentOn = r.out.contains("on=1");
                boolean backOff = r.out.contains("off=0");
                o.put("ok", r.fail == null && wentOn && backOff);
                o.put("stuck", wentOn && !backOff);
                o.put("detail", wentOn ? "" : "The phone refused the airplane mode command.");
                c.resolve(o);
            }
        });
    }

    @PluginMethod
    public void dataCycle(PluginCall call) {
        final int secs = clampInt(call, "seconds", 3, 2, 20);
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                String script = "svc data disable; sleep " + secs + "; svc data enable; echo done";
                Res r = sh(script, secs + 15);
                JSObject o = shellResult(r);
                o.put("ok", r.fail == null && r.out.contains("done"));
                c.resolve(o);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Private DNS                                                         */
    /* ------------------------------------------------------------------ */

    private Res readDns() {
        return sh("echo \"mode=$(settings get global private_dns_mode)\"; "
            + "echo \"host=$(settings get global private_dns_specifier)\"", 10);
    }

    private static String field(String out, String key) {
        Matcher m = Pattern.compile("(?m)^" + key + "=(.*)$").matcher(out == null ? "" : out);
        if (!m.find()) return "";
        String v = m.group(1).trim();
        return "null".equals(v) ? "" : v;
    }

    @PluginMethod
    public void getDns(PluginCall call) {
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                Res r = readDns();
                JSObject o = new JSObject();
                o.put("mode", field(r.out, "mode"));
                o.put("host", field(r.out, "host"));
                c.resolve(o);
            }
        });
    }

    @PluginMethod
    public void setDns(PluginCall call) {
        final String preset = call.getString("preset", "");
        final String mode;
        final String host;
        if ("cloudflare".equals(preset)) {
            mode = "hostname";
            host = "one.one.one.one";
        } else if ("google".equals(preset)) {
            mode = "hostname";
            host = "dns.google";
        } else if ("auto".equals(preset)) {
            mode = "opportunistic";
            host = null;
        } else if ("off".equals(preset)) {
            mode = "off";
            host = null;
        } else {
            call.reject("bad-preset");
            return;
        }
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                StringBuilder sb = new StringBuilder();
                if (host != null) sb.append("settings put global private_dns_specifier ").append(host).append("; ");
                sb.append("settings put global private_dns_mode ").append(mode);
                sh(sb.toString(), 10);
                Res r = readDns();
                String gotMode = field(r.out, "mode");
                String gotHost = field(r.out, "host");
                boolean ok = mode.equals(gotMode) && (host == null || host.equals(gotHost));
                JSObject o = new JSObject();
                o.put("ok", ok);
                o.put("mode", gotMode);
                o.put("host", gotHost);
                c.resolve(o);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Lock to 4G (experimental)                                           */
    /* ------------------------------------------------------------------ */

    private SharedPreferences prefs() {
        return getContext().getSharedPreferences("pulsecheck_tools", Context.MODE_PRIVATE);
    }

    private static String maskKey(int slot) {
        return "orig_mask_" + slot;
    }

    /** Reads the allowed-network-types bitmask. Returns null when the output is not one clear number. */
    private Long readMask(int slot, JSObject rawOut) {
        Res r = sh("cmd phone get-allowed-network-types-for-users -s " + slot + " 2>&1", 12);
        if (rawOut != null) rawOut.put("raw", clip(r.out, 300));
        Matcher m = Pattern.compile("\\d{2,}").matcher(r.out == null ? "" : r.out);
        Long found = null;
        while (m.find()) {
            long v;
            try {
                v = Long.parseLong(m.group());
            } catch (Exception e) {
                return null;
            }
            if (found != null && found.longValue() != v) return null;
            found = Long.valueOf(v);
        }
        return found;
    }

    private Res writeMask(int slot, long mask) {
        return sh("cmd phone set-allowed-network-types-for-users -s " + slot + " " + mask + " 2>&1", 12);
    }

    @PluginMethod
    public void netTypeGet(PluginCall call) {
        final int slot = slotOf(call);
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                JSObject o = new JSObject();
                Long mask = readMask(slot, o);
                o.put("readable", mask != null);
                if (mask != null) {
                    o.put("mask", String.valueOf(mask));
                    o.put("hasNr", (mask.longValue() & NR_BIT) != 0);
                }
                o.put("saved", prefs().contains(maskKey(slot)));
                c.resolve(o);
            }
        });
    }

    @PluginMethod
    public void netTypeLock(PluginCall call) {
        final int slot = slotOf(call);
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                JSObject o = new JSObject();
                Long cur = readMask(slot, null);
                if (cur == null) {
                    o.put("ok", false);
                    o.put("reason", "unreadable");
                    c.resolve(o);
                    return;
                }
                long original = cur.longValue();
                if ((original & NR_BIT) == 0) {
                    o.put("ok", true);
                    o.put("reason", "already");
                    c.resolve(o);
                    return;
                }
                long target = original & ~NR_BIT;
                if (target == 0L) {
                    o.put("ok", false);
                    o.put("reason", "unreadable");
                    c.resolve(o);
                    return;
                }
                prefs().edit().putLong(maskKey(slot), original).apply();
                writeMask(slot, target);
                Long after = readMask(slot, null);
                if (after == null || after.longValue() != target) {
                    writeMask(slot, original);
                    prefs().edit().remove(maskKey(slot)).apply();
                    o.put("ok", false);
                    o.put("reason", "ignored");
                    c.resolve(o);
                    return;
                }
                o.put("ok", true);
                o.put("reason", "locked");
                c.resolve(o);
            }
        });
    }

    @PluginMethod
    public void netTypeRestore(PluginCall call) {
        final int slot = slotOf(call);
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                JSObject o = new JSObject();
                SharedPreferences p = prefs();
                if (!p.contains(maskKey(slot))) {
                    o.put("ok", false);
                    o.put("reason", "nothing-saved");
                    c.resolve(o);
                    return;
                }
                long original = p.getLong(maskKey(slot), 0L);
                writeMask(slot, original);
                Long after = readMask(slot, null);
                if (after != null && after.longValue() == original) {
                    p.edit().remove(maskKey(slot)).apply();
                    o.put("ok", true);
                    o.put("reason", "restored");
                } else {
                    o.put("ok", false);
                    o.put("reason", "ignored");
                }
                c.resolve(o);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Modem report, ping, route                                           */
    /* ------------------------------------------------------------------ */

    @PluginMethod
    public void modem(PluginCall call) {
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                Res r = sh("dumpsys telephony.registry 2>/dev/null | grep -E "
                    + "'mServiceState|PhysicalChannelConfig|mCellBandwidths|isUsingCarrierAggregation|nrState|FrequencyRange'", 25);
                c.resolve(shellResult(r));
            }
        });
    }

    private static boolean okHost(String h) {
        return "1.1.1.1".equals(h) || "8.8.8.8".equals(h);
    }

    @PluginMethod
    public void ping(PluginCall call) {
        final String host = call.getString("host", "");
        final int count = clampInt(call, "count", 10, 1, 20);
        if (!okHost(host)) {
            call.reject("bad-host");
            return;
        }
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                Res r = sh("ping -c " + count + " -W 2 " + host + " 2>&1", count * 3 + 8);
                c.resolve(shellResult(r));
            }
        });
    }

    @PluginMethod
    public void route(PluginCall call) {
        final String host = call.getString("host", "");
        if (!okHost(host)) {
            call.reject("bad-host");
            return;
        }
        async(call, new Job() {
            @Override
            public void run(PluginCall c) {
                String script = "for t in 1 2 3 4 5 6 7 8; do echo \"HOP $t\"; "
                    + "r=$(ping -c 1 -t $t -W 2 " + host + " 2>&1); echo \"$r\" | head -n 4; "
                    + "echo \"$r\" | grep -q 'bytes from' && break; done";
                Res r = sh(script, 40);
                c.resolve(shellResult(r));
            }
        });
    }
}
