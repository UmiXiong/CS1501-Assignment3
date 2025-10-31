/*
 Diagnostic LZWTool - prints a numbered eviction (evict) snapshot on every replacement so encoder/decoder logs can be compared.
 Replace your project's LZWTool.java with this file for one diagnostic run.
 Keep TSTmod.java and BinaryStdIn/BinaryStdOut unchanged.
 Usage:
   javac *.java
   java LZWTool --mode compress --minW 3 --maxW 4 --policy lru --alphabet alphabets/ab.txt < TestFiles/ab_txt.txt > ab_compress.lzw 2> compress.log
   java LZWTool --mode expand < ab_compress.lzw > ab_expand.txt 2> expand.log
*/
import java.io.*;
import java.util.*;

public class LZWTool {

    private static final boolean DEBUG = false; // general debug
    // Eviction counter shared per run (encoder and decoder runs are separate processes)
    private static int evictCounter = 0;

    public static void main(String[] args) {
        String mode = null;
        int minW = 9, maxW = 16;
        String policy = "freeze";
        String alphabetPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode": mode = args[++i]; break;
                case "--minW": minW = Integer.parseInt(args[++i]); break;
                case "--maxW": maxW = Integer.parseInt(args[++i]); break;
                case "--policy": policy = args[++i]; break;
                case "--alphabet": alphabetPath = args[++i]; break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        if (mode == null) { System.err.println("Error: --mode is required"); System.exit(1); }
        if (minW > maxW) { System.err.println("Error: minW must be <= maxW"); System.exit(1); }

        try {
            if ("compress".equals(mode)) {
                if (alphabetPath == null) { System.err.println("Error: --alphabet required for compress"); System.exit(1); }
                compress(minW, maxW, policy, alphabetPath);
            } else if ("expand".equals(mode)) {
                expand();
            } else {
                System.err.println("Error: unknown mode " + mode); System.exit(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    // Read alphabet file: one symbol per line (non-empty), deduplicate preserving order
    private static List<String> readAlphabet(String path) throws IOException {
        List<String> alphabet = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty() && !seen.contains(line)) {
                    seen.add(line);
                    alphabet.add(line);
                }
            }
        }
        return alphabet;
    }

    private static void writeHeader(int minW, int maxW, String policy, List<String> alphabet) {
        BinaryStdOut.write(minW, 8);
        BinaryStdOut.write(maxW, 8);
        int policyCode = 0;
        switch (policy) { case "freeze": policyCode = 0; break; case "reset": policyCode = 1; break; case "lru": policyCode = 2; break; case "lfu": policyCode = 3; break; }
        BinaryStdOut.write(policyCode, 8);
        BinaryStdOut.write(alphabet.size(), 16);
        for (String s : alphabet) BinaryStdOut.write(s.charAt(0), 8);
    }

    private static HeaderInfo readHeader() {
        HeaderInfo h = new HeaderInfo();
        h.minW = BinaryStdIn.readInt(8);
        h.maxW = BinaryStdIn.readInt(8);
        int pc = BinaryStdIn.readInt(8);
        switch (pc) { case 0: h.policy = "freeze"; break; case 1: h.policy = "reset"; break; case 2: h.policy = "lru"; break; case 3: h.policy = "lfu"; break; default: h.policy = "freeze"; break; }
        int alphabetSize = BinaryStdIn.readInt(16);
        h.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) h.alphabet.add(String.valueOf(BinaryStdIn.readChar(8)));
        return h;
    }

    // Print small neighbor snapshot for reverse/codebook
    private static void printNeighbors(Map<Integer,String> map, int center, int alphabetSize, String tag) {
        int lo = Math.max(alphabetSize, center - 4);
        int hi = Math.min(center + 5, Math.max(center+1, lo + 9));
        System.err.println(tag + " neighbors [" + lo + ".." + (hi-1) + "]:");
        for (int i = lo; i < hi; i++) {
            String v = map.get(i);
            System.err.printf("  %d -> \"%s\"%n", i, v == null ? "null" : v);
        }
    }

    // Complete reverse snapshot for a small range (used for first few evicts if needed)
    private static void printRange(Map<Integer,String> map, int lo, int hi, String tag) {
        System.err.println(tag + " [" + lo + ".." + (hi-1) + "]:");
        for (int i = lo; i < hi; i++) {
            String v = map.get(i);
            System.err.printf("  %d -> \"%s\"%n", i, v == null ? "null" : v);
        }
    }

    // Compressor diagnostic version: print evict info and evictCounter
    private static void compress(int minW, int maxW, String policy, String alphabetPath) throws IOException {
        List<String> alphabet = readAlphabet(alphabetPath);
        writeHeader(minW, maxW, policy, alphabet);

        int W = minW;
        int maxCodeLimit = (1 << maxW) - 1;

        TSTmod<Integer> codebook = new TSTmod<>();
        Map<Integer,String> reverse = new HashMap<>();
        int nextCode = 0;
        for (String s : alphabet) { codebook.put(new StringBuilder(s), nextCode); reverse.put(nextCode, s); nextCode++; }

        Map<Integer,Integer> frequency = new HashMap<>();
        Map<Integer,Integer> lastUsed = new HashMap<>();
        for (int i = 0; i < alphabet.size(); i++) { frequency.put(i,0); lastUsed.put(i,0); }
        int timestamp = 0;

        System.err.printf("ENCODE: start minW=%d maxW=%d policy=%s alpha=%d maxLimit=%d%n", minW, maxW, policy, alphabet.size(), maxCodeLimit);

        StringBuilder current = new StringBuilder();
        String prevString = null; // last output string, kept for symmetric new pattern construction

        while (!BinaryStdIn.isEmpty()) {
            char c = BinaryStdIn.readChar(8);
            StringBuilder next = new StringBuilder(current).append(c);

            if (codebook.contains(next)) {
                current = next;
            } else {
                if (current.length() > 0) {
                    Integer code = codebook.get(current);
                    if (code != null) {
                        BinaryStdOut.write(code, W);
                        frequency.put(code, frequency.getOrDefault(code,0)+1);
                        lastUsed.put(code, ++timestamp);
                        System.err.printf("ENCODE: wrote code=%d W=%d nextCode=%d ts=%d current=\"%s\"%n", code, W, nextCode, timestamp, current.toString());
                        prevString = reverse.get(code);
                    }
                }

                // new pattern to add/replace: use prevString + c to mirror decoder behavior
                String newPattern = (prevString != null) ? prevString + c : next.toString();

                if (nextCode < maxCodeLimit) {
                    if (nextCode == (1<<W)-1 && W < maxW) {
                        System.err.printf("ENCODE: bump width %d -> %d (nextCode=%d)%n", W, W+1, nextCode);
                        W++;
                    }
                    codebook.put(new StringBuilder(newPattern), nextCode);
                    reverse.put(nextCode, newPattern);
                    frequency.put(nextCode, 0);
                    System.err.printf("ENCODE: add code=%d => \"%s\"%n", nextCode, newPattern);
                    nextCode++;
                } else {
                    // need to evict/replace according to policy
                    if ("lru".equals(policy)) {
                        int lru=-1, minTime=Integer.MAX_VALUE;
                        for (int i = alphabet.size(); i < nextCode; i++) {
                            if (reverse.containsKey(i)) {
                                int t = lastUsed.getOrDefault(i, 0);
                                if (t < minTime || (t == minTime && (lru == -1 || i < lru))) { minTime = t; lru = i; }
                            }
                        }
                        if (lru >= 0) {
                            evictCounter++;
                            // print snapshot BEFORE evict with evict id
                            System.err.printf("ENC_EVICT#%d BEFORE chosen=%d minTime=%d ts=%d prevString=\"%s\" newPattern=\"%s\"%n",
                                    evictCounter, lru, minTime, timestamp, prevString, newPattern);
                            printNeighbors(reverse, lru, alphabet.size(), "ENC_EVICT#" + evictCounter);

                            String old = reverse.get(lru);
                            reverse.remove(lru);
                            if (old != null) codebook.put(new StringBuilder(old), null);
                            codebook.put(new StringBuilder(newPattern), lru);
                            reverse.put(lru, newPattern);
                            frequency.put(lru, 0);

                            // print AFTER snapshot
                            System.err.printf("ENC_EVICT#%d AFTER chosen=%d old=\"%s\" -> new=\"%s\"%n",
                                    evictCounter, lru, old, newPattern);
                            printNeighbors(reverse, lru, alphabet.size(), "ENC_EVICT#" + evictCounter);
                        } else {
                            System.err.println("ENCODE: LRU found no candidate");
                        }
                    } else if ("lfu".equals(policy)) {
                        int lfu=-1, minFreq=Integer.MAX_VALUE;
                        for (int i = alphabet.size(); i < nextCode; i++) {
                            if (reverse.containsKey(i)) {
                                int f = frequency.getOrDefault(i, 0);
                                if (f < minFreq || (f == minFreq && (lfu == -1 || i < lfu))) { minFreq = f; lfu = i; }
                            }
                        }
                        if (lfu >= 0) {
                            evictCounter++;
                            System.err.printf("ENC_EVICT#%d BEFORE chosen=%d minFreq=%d ts=%d prevString=\"%s\" newPattern=\"%s\"%n",
                                    evictCounter, lfu, minFreq, timestamp, prevString, newPattern);
                            printNeighbors(reverse, lfu, alphabet.size(), "ENC_EVICT#" + evictCounter);

                            String old = reverse.get(lfu);
                            reverse.remove(lfu);
                            if (old != null) codebook.put(new StringBuilder(old), null);
                            codebook.put(new StringBuilder(newPattern), lfu);
                            reverse.put(lfu, newPattern);
                            frequency.put(lfu, 0);

                            System.err.printf("ENC_EVICT#%d AFTER chosen=%d old=\"%s\" -> new=\"%s\"%n",
                                    evictCounter, lfu, old, newPattern);
                            printNeighbors(reverse, lfu, alphabet.size(), "ENC_EVICT#" + evictCounter);
                        } else {
                            System.err.println("ENCODE: LFU found no candidate");
                        }
                    } else if ("reset".equals(policy)) {
                        // reset: print reset event (evictCounter not incremented)
                        System.err.printf("ENCODE: RESET at ts=%d%n", timestamp);
                        codebook = new TSTmod<>();
                        reverse.clear();
                        frequency.clear();
                        lastUsed.clear();
                        nextCode = 0;
                        for (String s : alphabet) { codebook.put(new StringBuilder(s), nextCode); reverse.put(nextCode, s); frequency.put(nextCode,0); lastUsed.put(nextCode,0); nextCode++; }
                        W = minW;
                        if (nextCode == (1<<W)-1 && W < maxW) W++;
                        if (nextCode < maxCodeLimit) {
                            codebook.put(new StringBuilder(newPattern), nextCode);
                            reverse.put(nextCode, newPattern);
                            frequency.put(nextCode,0);
                            nextCode++;
                        }
                    } else {
                        System.err.println("ENCODE: freeze policy - not adding");
                    }
                }

                current = new StringBuilder().append(c);
            }
        }

        if (current.length() > 0) {
            Integer code = codebook.get(current);
            if (code != null) {
                BinaryStdOut.write(code, W);
                System.err.printf("ENCODE: final write code=%d current=\"%s\"%n", code, current.toString());
            }
        }

        int stop = (1<<W) - 1;
        BinaryStdOut.write(stop, W);
        System.err.println("ENCODE: wrote STOP=" + stop);
        BinaryStdOut.close();
        System.err.println("ENCODE: finished");
    }

    // Decoder diagnostic version: prints evict info and evictCounter (separate process)
    private static void expand() throws IOException {
        HeaderInfo info = readHeader();
        int W = info.minW;
        int maxCodeLimit = (1 << info.maxW) - 1;

        Map<Integer,String> codebook = new HashMap<>();
        int nextCode = 0;
        for (String s : info.alphabet) codebook.put(nextCode++, s);

        Map<Integer,Integer> frequency = new HashMap<>();
        Map<Integer,Integer> lastUsed = new HashMap<>();
        for (int i = 0; i < info.alphabet.size(); i++) { frequency.put(i,0); lastUsed.put(i,0); }
        int timestamp = 0;

        System.err.printf("DECODE: start minW=%d maxW=%d policy=%s alpha=%d maxLimit=%d%n",
                info.minW, info.maxW, info.policy, info.alphabet.size(), maxCodeLimit);

        if (nextCode == (1<<W)-1 && W < info.maxW) W++;
        if (BinaryStdIn.isEmpty()) { BinaryStdOut.close(); return; }

        int prevCode = BinaryStdIn.readInt(W);
        System.err.printf("DECODE: read first code=%d W=%d nextCode=%d%n", prevCode, W, nextCode);
        String prevString = codebook.get(prevCode);
        if (prevString == null) { BinaryStdOut.close(); return; }
        BinaryStdOut.write(prevString);
        frequency.put(prevCode, frequency.getOrDefault(prevCode,0) + 1);
        lastUsed.put(prevCode, ++timestamp);

        while (!BinaryStdIn.isEmpty()) {
            if (nextCode == (1<<W)-1 && W < info.maxW) W++;
            int code = BinaryStdIn.readInt(W);
            System.err.printf("DECODE: read code=%d W=%d nextCode=%d%n", code, W, nextCode);
            int stop = (1<<W)-1;
            if (code == stop) { System.err.printf("DECODE: hit STOP=%d%n", stop); break; }

            String entry;
            if (codebook.containsKey(code)) entry = codebook.get(code);
            else if (code == nextCode) entry = prevString + prevString.charAt(0);
            else throw new RuntimeException("Invalid code: " + code);

            BinaryStdOut.write(entry);
            frequency.put(code, frequency.getOrDefault(code,0) + 1);
            lastUsed.put(code, ++timestamp);

            if (nextCode < maxCodeLimit) {
                if (nextCode == (1<<W)-1 && W < info.maxW) W++;
                String newEntry = prevString + entry.charAt(0);
                codebook.put(nextCode, newEntry);
                frequency.put(nextCode, 0);
                System.err.printf("DECODE: add code=%d => \"%s\"%n", nextCode, newEntry);
                nextCode++;
            } else {
                if ("lru".equals(info.policy)) {
                    int lru=-1, minTime=Integer.MAX_VALUE;
                    for (int i = info.alphabet.size(); i < nextCode; i++) {
                        if (codebook.containsKey(i)) {
                            int t = lastUsed.getOrDefault(i,0);
                            if (t < minTime || (t == minTime && (lru == -1 || i < lru))) { minTime = t; lru = i; }
                        }
                    }
                    if (lru >= 0) {
                        evictCounter++;
                        String newEntry = prevString + entry.charAt(0);
                        System.err.printf("DEC_EVICT#%d BEFORE chosen=%d minTime=%d ts=%d prevString=\"%s\" entry=\"%s\" newEntry=\"%s\"%n",
                                evictCounter, lru, minTime, timestamp, prevString, entry, newEntry);
                        printNeighbors(codebook, lru, info.alphabet.size(), "DEC_EVICT#" + evictCounter);

                        codebook.put(lru, newEntry);
                        frequency.put(lru, 0);

                        System.err.printf("DEC_EVICT#%d AFTER chosen=%d new=\"%s\"%n", evictCounter, lru, newEntry);
                        printNeighbors(codebook, lru, info.alphabet.size(), "DEC_EVICT#" + evictCounter);
                    } else {
                        System.err.println("DECODE: LRU found no candidate");
                    }
                } else if ("lfu".equals(info.policy)) {
                    int lfu=-1, minFreq=Integer.MAX_VALUE;
                    for (int i = info.alphabet.size(); i < nextCode; i++) {
                        if (codebook.containsKey(i)) {
                            int f = frequency.getOrDefault(i,0);
                            if (f < minFreq || (f == minFreq && (lfu == -1 || i < lfu))) { minFreq = f; lfu = i; }
                        }
                    }
                    if (lfu >= 0) {
                        evictCounter++;
                        String newEntry = prevString + entry.charAt(0);
                        System.err.printf("DEC_EVICT#%d BEFORE chosen=%d minFreq=%d ts=%d prevString=\"%s\" entry=\"%s\" newEntry=\"%s\"%n",
                                evictCounter, lfu, minFreq, timestamp, prevString, entry, newEntry);
                        printNeighbors(codebook, lfu, info.alphabet.size(), "DEC_EVICT#" + evictCounter);

                        codebook.put(lfu, newEntry);
                        frequency.put(lfu, 0);

                        System.err.printf("DEC_EVICT#%d AFTER chosen=%d new=\"%s\"%n", evictCounter, lfu, newEntry);
                        printNeighbors(codebook, lfu, info.alphabet.size(), "DEC_EVICT#" + evictCounter);
                    } else {
                        System.err.println("DECODE: LFU found no candidate");
                    }
                } else if ("reset".equals(info.policy)) {
                    System.err.printf("DECODE: RESET at ts=%d%n", timestamp);
                    codebook.clear();
                    nextCode = 0;
                    for (String s : info.alphabet) { codebook.put(nextCode, s); frequency.put(nextCode,0); lastUsed.put(nextCode,0); nextCode++; }
                    W = info.minW;
                    if (nextCode == (1<<W)-1 && W < info.maxW) W++;
                    if (nextCode < maxCodeLimit) {
                        String newEntry = prevString + entry.charAt(0);
                        codebook.put(nextCode, newEntry);
                        frequency.put(nextCode,0);
                        nextCode++;
                    }
                } else {
                    System.err.println("DECODE: freeze policy - not adding");
                }
            }

            prevString = entry;
            prevCode = code;
        }

        BinaryStdOut.close();
        System.err.println("DECODE: finished");
    }

    private static class HeaderInfo { int minW, maxW; String policy; List<String> alphabet; }
}