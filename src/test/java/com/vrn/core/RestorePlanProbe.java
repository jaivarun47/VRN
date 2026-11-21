package com.vrn.core;

import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.TreeMap;

/** Standalone packaged-API/constrained-heap probe; no JUnit runtime dependency. */
public final class RestorePlanProbe {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        Map<String,String> before=fingerprint(root);
        var plan=new RestorePlanner(root).plan(args[1]);
        if(!before.equals(fingerprint(root))) throw new AssertionError("Planning changed repository bytes/tree");
        System.out.println("READ_ONLY=true");
        System.out.println("SAFE="+plan.isSafe());
        System.out.println("HEAD="+plan.selection().headId());
        for(var action:plan.actions()) System.out.println(action.operation()+" "+action.safety()+" "+action.path()+" target="+action.targetHash());
    }

    static Map<String,String> fingerprint(Path root) throws Exception {
        var result=new TreeMap<String,String>();
        try(var entries=Files.walk(root)) {
            for(Path path:entries.toList()) {
                var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
                String value=attrs.isSymbolicLink()||attrs.isOther()?"link/reparse":attrs.isDirectory()?"directory":new FileHasher().hash(path);
                result.put(root.relativize(path).toString(),value);
            }
        }
        return result;
    }
}
