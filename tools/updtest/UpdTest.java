import com.littlesauce.usbnaslink.Update;

public class UpdTest {
    public static void main(String[] a) {
        System.out.println("cmp 1.10.0 vs 1.9.9  = " + Update.compare("1.10.0", "1.9.9"));
        System.out.println("cmp v1.2 vs 1.2.0     = " + Update.compare("v1.2", "1.2.0"));
        System.out.println("cmp 1.0 vs 1.0.1      = " + Update.compare("1.0", "1.0.1"));
        System.out.println("cmp 20261122 vs 20261123 = " + Update.compare("20261122", "20261123"));
        System.out.println("trimTag V3.4.5        = " + Update.trimTag("V3.4.5"));

        System.out.println("--- live check win ---");
        Update.Info w = Update.check("1.0.0", "-win64.zip");
        System.out.println("ok=" + w.ok + " err=" + w.error + " tag=" + w.tag
                + " newer=" + Update.newer(w, "1.0.0")
                + " asset=" + (w.asset == null ? "null" : w.asset.name + " " + w.asset.humanSize()));

        System.out.println("--- live check apk ---");
        Update.Info p = Update.check("1.0.0", "-arm64-v8a.apk");
        System.out.println("ok=" + p.ok + " err=" + p.error
                + " asset=" + (p.asset == null ? "null" : p.asset.name));
    }
}
