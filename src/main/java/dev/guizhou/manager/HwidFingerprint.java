package dev.guizhou.manager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Hardware fingerprint, byte-for-byte the same factor chain and hash as the
 * launcher (Rust WMI / Java OSHI builds verified identical): CPU ProcessorID +
 * board manufacturer/model/serial + system-disk serial + Windows MachineGuid,
 * salted with "gzwx.v1|". The heartbeat must send the exact same string the
 * account was bound with, otherwise the server answers hwid mismatch.
 */
public final class HwidFingerprint {

    private HwidFingerprint() {}

    public static String compute() {
        StringBuilder sb = new StringBuilder();
        try {
            oshi.SystemInfo si = new oshi.SystemInfo();
            oshi.hardware.HardwareAbstractionLayer hw = si.getHardware();
            sb.append(n(hw.getProcessor().getProcessorIdentifier().getProcessorID())).append('|');
            oshi.hardware.ComputerSystem cs = hw.getComputerSystem();
            sb.append(n(cs.getManufacturer())).append('/')
              .append(n(cs.getModel())).append('/')
              .append(n(cs.getBaseboard() == null ? "" : n(cs.getBaseboard().getSerialNumber()))).append('|');
            String drive = System.getenv("SystemDrive");
            if (drive == null || drive.isEmpty()) drive = "C:";
            boolean diskFound = false;
            for (oshi.hardware.HWDiskStore d : hw.getDiskStores()) {
                if (diskFound) break;
                for (oshi.hardware.HWPartition pt : d.getPartitions()) {
                    String mp = pt.getMountPoint();
                    if (mp != null && mp.toLowerCase().startsWith(drive.toLowerCase())) {
                        sb.append(n(d.getSerial()));
                        diskFound = true;
                        break;
                    }
                }
            }
            sb.append('|');
        } catch (Throwable t) { /* OSHI failure degrades to MachineGuid-only */ }
        sb.append(machineGuid());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(("gzwx.v1|" + sb).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("hwid hash failed", e);
        }
    }

    private static String n(String s) { return s == null ? "" : s.trim(); }

    /** HKLM\SOFTWARE\Microsoft\Cryptography\MachineGuid — changes only on OS reinstall */
    private static String machineGuid() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"reg", "query",
                    "HKLM\\SOFTWARE\\Microsoft\\Cryptography", "/v", "MachineGuid"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.contains("MachineGuid")) {
                    String[] parts = line.trim().split("\\s+");
                    return parts[parts.length - 1].trim();
                }
            }
            r.close();
            p.waitFor();
        } catch (Throwable t) { /* ignore */ }
        return "";
    }
}
