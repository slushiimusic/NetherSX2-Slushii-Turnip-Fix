import com.android.tools.smali.dexlib2.DexFileFactory;
import com.android.tools.smali.dexlib2.Opcodes;
import com.android.tools.smali.dexlib2.iface.ClassDef;
import com.android.tools.smali.dexlib2.iface.DexFile;
import com.android.tools.smali.dexlib2.writer.io.FileDataStore;
import com.android.tools.smali.dexlib2.writer.pool.DexPool;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

/** Merge shim classes into an existing classes.dex, replacing prior shim package. */
public final class MergeShimDex {
    private static final String SHIM_PREFIX = "Lxyz/aethersx2/android/shim/";

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            System.err.println("usage: MergeShimDex base.dex shim.dex out.dex");
            System.exit(1);
        }
        File baseFile = new File(args[0]);
        File shimFile = new File(args[1]);
        File outFile = new File(args[2]);

        DexFile base = DexFileFactory.loadDexFile(baseFile, Opcodes.getDefault());
        DexFile shim = DexFileFactory.loadDexFile(shimFile, Opcodes.getDefault());

        Set<String> shimTypes = new HashSet<>();
        for (ClassDef c : shim.getClasses()) {
            shimTypes.add(c.getType());
        }

        DexPool pool = new DexPool(Opcodes.getDefault());
        for (ClassDef c : base.getClasses()) {
            String type = c.getType();
            if (type.startsWith(SHIM_PREFIX) || shimTypes.contains(type)) {
                continue;
            }
            pool.internClass(c);
        }
        for (ClassDef c : shim.getClasses()) {
            pool.internClass(c);
        }
        pool.writeTo(new FileDataStore(outFile));
        System.out.println("merged " + baseFile.getName() + " + " + shimFile.getName()
                + " -> " + outFile.getName() + " (" + shimTypes.size() + " shim classes)");
    }
}
