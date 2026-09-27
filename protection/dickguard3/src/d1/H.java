package d1;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Hardware fingerprint — byte-for-byte the same factor chain and hash as the
 * launcher and the paid client (OSHI: CPU ProcessorID + Win32_BaseBoard
 * manufacturer/model/serial + PHYSICALDRIVE0 serial + MachineGuid, salted
 * gzwx.v1|). The heartbeat must send exactly the string the account was
 * bound with.
 */
final class H {
    private H() {}

    static String compute() {
        StringBuilder sb = new StringBuilder();
        try {
            oshi.SystemInfo si = new oshi.SystemInfo();
            oshi.hardware.HardwareAbstractionLayer hw = si.getHardware();
            sb.append(n(hw.getProcessor().getProcessorIdentifier().getProcessorID())).append('|');
            oshi.hardware.Baseboard bb = hw.getComputerSystem().getBaseboard();
            String board = bb == null ? ""
                    : raw(bb.getManufacturer()) + "/" + raw(bb.getModel()) + "/" + raw(bb.getSerialNumber());
            sb.append(board.trim()).append('|');
            String drive = System.getenv("SystemDrive");
            if (drive == null || drive.isEmpty()) drive = "C:";
            boolean diskFound = false;
            for (oshi.hardware.HWDiskStore d : hw.getDiskStores()) {
                if (d.getName() != null && d.getName().endsWith("PHYSICALDRIVE0")) {
                    sb.append(n(d.getSerial()));
                    diskFound = true;
                    break;
                }
            }
            if (!diskFound) {
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
            }
            sb.append('|');
        } catch (Throwable t) { /* OSHI failure degrades to MachineGuid-only */ }
        sb.append(machineGuid());
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest((S.g(5) + sb).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : h) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String n(String s) { return s == null ? "" : s.trim(); }

    private static String raw(String s) { return s == null ? "" : s; }

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
