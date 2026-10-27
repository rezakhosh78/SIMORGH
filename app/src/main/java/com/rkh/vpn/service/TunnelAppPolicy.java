package com.rkh.vpn.service;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.VpnService;
import android.util.AtomicFile;
import java.io.*;
import java.util.*;

/** One atomic, disk-backed policy shared by every VPN process. */
public final class TunnelAppPolicy {
    private TunnelAppPolicy() {}
    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getFilesDir(), "tunnel-app-policy.properties"));
    }
    private static Properties read(Context c) {
        Properties p = new Properties();
        AtomicFile f = file(c);
        try (InputStream in = f.openRead()) { p.load(in); }
        catch (FileNotFoundException e) { /* No global policy has been configured yet. */ }
        catch (IOException e) { throw new IllegalStateException("Cannot read Tunnel Apps policy", e); }
        return p;
    }
    public static String mode(Context c) { return read(c).getProperty("mode", "all"); }
    public static Set<String> packages(Context c, String mode) {
        return packages(read(c), mode);
    }
    private static Set<String> packages(Properties p, String mode) {
        Set<String> result = new LinkedHashSet<>();
        for (String s : p.getProperty(mode, "").split(",")) if (!s.isEmpty()) result.add(s);
        return result;
    }
    private static void write(Context c, Properties p) {
        AtomicFile f = file(c);
        FileOutputStream out = null;
        try { out = f.startWrite(); p.store(out, "SIMORGH global Tunnel Apps"); f.finishWrite(out); }
        catch (IOException e) { if (out != null) f.failWrite(out); throw new IllegalStateException("Cannot save Tunnel Apps policy", e); }
    }
    public static synchronized void setMode(Context c, String mode) {
        if (!Arrays.asList("all", "only", "exclude").contains(mode)) throw new IllegalArgumentException("Invalid policy");
        Properties p = read(c); p.setProperty("mode", mode); write(c, p);
    }
    public static synchronized void toggle(Context c, String mode, String pkg) {
        if ("all".equals(mode) || c.getPackageName().equals(pkg)) return;
        Properties p = read(c); Set<String> apps = packages(p, mode);
        if (!apps.remove(pkg)) apps.add(pkg);
        p.setProperty(mode, String.join(",", apps)); write(c, p);
    }
    public static synchronized void clear(Context c, String mode) {
        Properties p = read(c); p.setProperty(mode, ""); write(c, p);
    }
    public static void apply(Context c, VpnService.Builder builder) {
        Properties p = read(c);
        String mode = p.getProperty("mode", "all");
        Set<String> apps = packages(p, mode);
        if ("only".equals(mode)) {
            int applied = 0;
            for (String pkg : apps) {
                if (c.getPackageName().equals(pkg)) continue;
                try { builder.addAllowedApplication(pkg); applied++; }
                catch (PackageManager.NameNotFoundException ignored) { }
            }
            // Never silently tunnel every app when an allowlist is empty/stale.
            if (applied == 0) throw new IllegalStateException("Tunnel Apps: select at least one installed app in Only mode");
        } else {
            try { builder.addDisallowedApplication(c.getPackageName()); }
            catch (PackageManager.NameNotFoundException e) { throw new IllegalStateException(e); }
            if ("exclude".equals(mode)) for (String pkg : apps) {
                if (c.getPackageName().equals(pkg)) continue;
                try { builder.addDisallowedApplication(pkg); }
                catch (PackageManager.NameNotFoundException ignored) { }
            }
        }
    }
}
