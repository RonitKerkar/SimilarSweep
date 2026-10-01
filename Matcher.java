package com.ronit.similarsweep;

import java.util.*;

/** Heuristic similarity scores, NOT probabilities or reliable app identification. */
public final class Matcher {
    public static final class Feature {
        public float[] layout;
        public long hash;
        public float aspect;
        public String text = "";
        public String app = "";
    }
    public static String appHint(String raw) {
        String s = " " + raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ") + " ";
        if (s.contains(" youtube ") || (s.contains(" subscribe ") && (s.contains(" shorts ") || s.contains(" views ")))) return "YouTube";
        if (s.contains(" discord ") || (s.contains(" direct messages ") && s.contains(" servers "))) return "Discord";
        if (s.contains(" twitter ") || (s.contains(" reposts ") && (s.contains(" quotes ") || s.contains(" likes "))) || (s.contains(" for you ") && s.contains(" following ") && s.contains(" grok "))) return "X / Twitter";
        return "";
    }
    private static Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        for (String w : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) if (w.length() >= 4) out.add(w);
        return out;
    }
    public static double score(Feature a, Feature b, boolean nearDuplicate) {
        double hash = 1.0 - Long.bitCount(a.hash ^ b.hash) / 64.0;
        double diff = 0;
        for (int i=0; i<a.layout.length; i++) diff += Math.abs(a.layout[i]-b.layout[i]);
        double layout = Math.max(0, 1 - diff/a.layout.length);
        double aspect = Math.min(a.aspect,b.aspect)/Math.max(a.aspect,b.aspect);
        if (nearDuplicate) return Math.min(100, 100*(0.55*hash+0.35*layout+0.10*aspect));
        Set<String> aw = words(a.text), bw = words(b.text);
        Set<String> intersection = new HashSet<>(aw); intersection.retainAll(bw);
        double text = aw.isEmpty() || bw.isEmpty() ? 0 : intersection.size()/(double)Math.min(aw.size(),bw.size());
        double base = 100*(0.5*layout+0.20*hash+0.15*aspect+0.15*text);
        if (!a.app.isEmpty() && a.app.equals(b.app)) base = Math.max(base, 83 + 12*layout);
        // A visible, conflicting app hint is stronger evidence than a generic dark layout.
        if (!a.app.isEmpty() && !b.app.isEmpty() && !a.app.equals(b.app)) base = Math.min(base, 60);
        return Math.max(0,Math.min(100,base));
    }
}
