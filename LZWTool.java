import java.io.*;
import java.util.*;

/**
 * LZWTool - A configurable LZW compression and decompression tool
 * Supports variable codeword width, custom alphabets, and multiple eviction policies
 */
public class LZWTool {

//    private static boolean DEBUG = false;

    public static void main(String[] args) {
        // Parse command-line arguments
        String mode = null;
        int minW = 9;
        int maxW = 16;
        String policy = "freeze";
        String alphabetPath = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--mode":
                    mode = args[++i];
                    break;
                case "--minW":
                    minW = Integer.parseInt(args[++i]);
                    break;
                case "--maxW":
                    maxW = Integer.parseInt(args[++i]);
                    break;
                case "--policy":
                    policy = args[++i];
                    break;
                case "--alphabet":
                    alphabetPath = args[++i];
                    break;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    System.exit(2);
            }
        }

        // Validate arguments
        if (mode == null) {
            System.err.println("Error: --mode is required");
            System.exit(1);
        }

        if (minW > maxW) {
            System.err.println("Error: minW must be <= maxW");
            System.exit(1);
        }

        // Execute compression or expansion
        try {
            if (mode.equals("compress")) {
                if (alphabetPath == null) {
                    System.err.println("Error: --alphabet is required for compression");
                    System.exit(1);
                }
                compress(minW, maxW, policy, alphabetPath);
            } else if (mode.equals("expand")) {
                expand();
            } else {
                System.err.println("Error: mode must be 'compress' or 'expand'");
                System.exit(1);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Read alphabet from file
     */
    private static List<String> readAlphabet(String path) throws IOException {
        List<String> alphabet = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isEmpty() && !seen.contains(line)) {
                    seen.add(line);
                    alphabet.add(line);
                }
            }
        }

        return alphabet;
    }

    /**
     * Write header to compressed file
     */
    private static void writeHeader(int minW, int maxW, String policy, List<String> alphabet) {
        // Write minW (1 byte)
        BinaryStdOut.write(minW, 8);

        // Write maxW (1 byte)
        BinaryStdOut.write(maxW, 8);

        // Write policy as integer (1 byte)
        int policyCode = 0;
        switch (policy) {
            case "freeze": policyCode = 0; break;
            case "reset": policyCode = 1; break;
            case "lru": policyCode = 2; break;
            case "lfu": policyCode = 3; break;
        }
        BinaryStdOut.write(policyCode, 8);

        // Write alphabet size (2 bytes)
        BinaryStdOut.write(alphabet.size(), 16);

        // Write each alphabet symbol (1 byte per symbol)
        for (String symbol : alphabet) {
            BinaryStdOut.write(symbol.charAt(0), 8);
        }
    }

    /**
     * Read header from compressed file
     */
    private static HeaderInfo readHeader() {
        HeaderInfo info = new HeaderInfo();

        // Read minW
        info.minW = BinaryStdIn.readInt(8);

        // Read maxW
        info.maxW = BinaryStdIn.readInt(8);

        // Read policy
        int policyCode = BinaryStdIn.readInt(8);
        switch (policyCode) {
            case 0: info.policy = "freeze"; break;
            case 1: info.policy = "reset"; break;
            case 2: info.policy = "lru"; break;
            case 3: info.policy = "lfu"; break;
        }

        // Read alphabet size
        int alphabetSize = BinaryStdIn.readInt(16);

        // Read alphabet
        info.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++) {
            char c = BinaryStdIn.readChar(8);
            info.alphabet.add(String.valueOf(c));
        }


        return info;
    }

    /**
     * Compress input using LZW algorithm
     */
    private static void compress(int minW, int maxW, String policy, String alphabetPath) throws IOException {
        // Read alphabet from file
        List<String> alphabet = readAlphabet(alphabetPath);

        // Write header
        writeHeader(minW, maxW, policy, alphabet);

        // Initialize compression state
        int W = minW;
        int maxCodeLimit = (1 << maxW);

        // Build initial codebook
        TSTmod<Integer> codebook = new TSTmod<>();
        Map<Integer, String> reverseCodebook = new HashMap<>();
        int nextCode = 0;

        for (String symbol : alphabet) {
            codebook.put(new StringBuilder(symbol), nextCode);
            reverseCodebook.put(nextCode, symbol);
            nextCode++;
        }

        // Tracking for eviction policies
        Map<Integer, Integer> frequency = new HashMap<>();
        Map<Integer, Integer> lastUsed = new HashMap<>();
        int timestamp = 0;

        for (int i = 0; i < alphabet.size(); i++) {
            frequency.put(i, 0);
            lastUsed.put(i, 0);
        }

        // Process input
        StringBuilder current = new StringBuilder();

        while (!BinaryStdIn.isEmpty()) {
            char c = BinaryStdIn.readChar(8);
            StringBuilder next = new StringBuilder(current).append(c);

            if (codebook.contains(next)) {
                current = next;
            } else {
                // Output code for current
                if (current.length() > 0) {
                    Integer code = codebook.get(current);
                    if (code != null) {
                        BinaryStdOut.write(code, W);
                        frequency.put(code, frequency.getOrDefault(code, 0) + 1);
                        lastUsed.put(code, timestamp++);
                    }
                }

                // Try to add new pattern
                if (nextCode < maxCodeLimit) {
                    // Increase width if needed BEFORE adding the new code
                    if (nextCode == (1 << W) && W < maxW) {
                        W++;
                    }

                    codebook.put(new StringBuilder(next), nextCode);
                    reverseCodebook.put(nextCode, next.toString());
                    frequency.put(nextCode, 0);
                    lastUsed.put(nextCode, timestamp);
                    nextCode++;
                } else {
                    // Codebook full - apply eviction policy
                    if (policy.equals("reset")) {
                        // Reset to alphabet only
                        codebook = new TSTmod<>();
                        reverseCodebook.clear();
                        nextCode = 0;

                        for (String symbol : alphabet) {
                            codebook.put(new StringBuilder(symbol), nextCode);
                            reverseCodebook.put(nextCode, symbol);
                            frequency.put(nextCode, 0);
                            lastUsed.put(nextCode, timestamp);
                            nextCode++;
                        }

                        W = minW;

                        // Add the new pattern
                        if (nextCode == (1 << W) && W < maxW) {
                            W++;
                        }

                        codebook.put(new StringBuilder(next), nextCode);
                        reverseCodebook.put(nextCode, next.toString());
                        frequency.put(nextCode, 0);
                        lastUsed.put(nextCode, timestamp);
                        nextCode++;
                    } else if (policy.equals("lru")) {
                        // Find LRU code (excluding alphabet)
                        int lruCode = -1;
                        int minTime = Integer.MAX_VALUE;

                        for (int i = alphabet.size(); i < nextCode; i++) {
                            if (reverseCodebook.containsKey(i)) {
                                int time = lastUsed.getOrDefault(i, 0);
                                if (time < minTime) {
                                    minTime = time;
                                    lruCode = i;
                                }
                            }
                        }

                        if (lruCode >= 0) {
                            String oldPattern = reverseCodebook.get(lruCode);
                            reverseCodebook.remove(lruCode);

                            // Replace with new pattern
                            codebook.put(new StringBuilder(next), lruCode);
                            reverseCodebook.put(lruCode, next.toString());
                            frequency.put(lruCode, 0);
                            lastUsed.put(lruCode, timestamp);
                        }
                    } else if (policy.equals("lfu")) {
                        // Find LFU code (excluding alphabet)
                        int lfuCode = -1;
                        int minFreq = Integer.MAX_VALUE;

                        for (int i = alphabet.size(); i < nextCode; i++) {
                            if (reverseCodebook.containsKey(i)) {
                                int freq = frequency.getOrDefault(i, 0);
                                if (freq < minFreq) {
                                    minFreq = freq;
                                    lfuCode = i;
                                }
                            }
                        }

                        if (lfuCode >= 0) {
                            String oldPattern = reverseCodebook.get(lfuCode);
                            reverseCodebook.remove(lfuCode);

                            // Replace with new pattern
                            codebook.put(new StringBuilder(next), lfuCode);
                            reverseCodebook.put(lfuCode, next.toString());
                            frequency.put(lfuCode, 0);
                            lastUsed.put(lfuCode, timestamp);
                        }
                    }
                    // else freeze - do nothing
                }

                current = new StringBuilder().append(c);
            }
        }

        // Output final code
        if (current.length() > 0) {
            Integer code = codebook.get(current);
            if (code != null) {
                BinaryStdOut.write(code, W);
            }
        }

        // Write stop code (use maximum possible value for current width as EOF marker)
        int stopCode = (1 << W) - 1;
        BinaryStdOut.write(stopCode, W);

        BinaryStdOut.close();
    }

    /**
     * Expand compressed input
     */
    private static void expand() throws IOException {
        // Read header
        HeaderInfo info = readHeader();

        int W = info.minW;
        int maxCodeLimit = (1 << info.maxW);

        // Build initial codebook
        Map<Integer, String> codebook = new HashMap<>();
        int nextCode = 0;

        for (String symbol : info.alphabet) {
            codebook.put(nextCode++, symbol);
        }

        // Tracking for eviction policies
        Map<Integer, Integer> frequency = new HashMap<>();
        Map<Integer, Integer> lastUsed = new HashMap<>();
        int timestamp = 0;

        for (int i = 0; i < info.alphabet.size(); i++) {
            frequency.put(i, 0);
            lastUsed.put(i, 0);
        }

        // Possibly increase width BEFORE reading first code if initial codebook size reaches the current width capacity
        if (nextCode == (1 << W) && W < info.maxW) {
            W++;
        }

        // Read first code
        if (BinaryStdIn.isEmpty()) {
            BinaryStdOut.close();
            return;
        }

        int prevCode = BinaryStdIn.readInt(W);
        String prevString = codebook.get(prevCode);

        if (prevString == null) {
            BinaryStdOut.close();
            return;
        }

        BinaryStdOut.write(prevString);
        frequency.put(prevCode, frequency.getOrDefault(prevCode, 0) + 1);
        lastUsed.put(prevCode, timestamp++);

        // Process remaining codes
        while (!BinaryStdIn.isEmpty()) {
            // Possibly increase width BEFORE reading the next code so decoder stays in sync with encoder
            if (nextCode == (1 << W) && W < info.maxW) {
                W++;
            }

            int code;
            try {
                code = BinaryStdIn.readInt(W);
            } catch (NoSuchElementException e) {
                // End of stream reached
                break;
            }


            // Check for stop code
            int stopCode = (1 << W) - 1;
            if (code == stopCode) {
                break;
            }

            String entry;

            if (codebook.containsKey(code)) {
                entry = codebook.get(code);
            } else if (code == nextCode) {
                // Special case: code not yet in codebook
                entry = prevString + prevString.charAt(0);
            } else {
                throw new RuntimeException("Invalid code: " + code);
            }

            BinaryStdOut.write(entry);
            frequency.put(code, frequency.getOrDefault(code, 0) + 1);
            lastUsed.put(code, timestamp++);

            // Add new entry to codebook
            if (nextCode < maxCodeLimit) {
                // Check if we need to increase width BEFORE adding
                if (nextCode == (1 << W) && W < info.maxW) {
                    W++;
                }

                String newEntry = prevString + entry.charAt(0);
                codebook.put(nextCode, newEntry);
                frequency.put(nextCode, 0);
                lastUsed.put(nextCode, timestamp);
                nextCode++;
            } else {
                // Codebook full - apply eviction policy
                if (info.policy.equals("reset")) {
                    // Reset to alphabet only
                    codebook.clear();
                    nextCode = 0;

                    for (String symbol : info.alphabet) {
                        codebook.put(nextCode, symbol);
                        frequency.put(nextCode, 0);
                        lastUsed.put(nextCode, timestamp);
                        nextCode++;
                    }

                    W = info.minW;

                    // Add the new pattern
                    if (nextCode == (1 << W) && W < info.maxW) {
                        W++;
                    }

                    String newEntry = prevString + entry.charAt(0);
                    codebook.put(nextCode, newEntry);
                    frequency.put(nextCode, 0);
                    lastUsed.put(nextCode, timestamp);
                    nextCode++;
                } else if (info.policy.equals("lru")) {
                    // Find LRU code (excluding alphabet)
                    int lruCode = -1;
                    int minTime = Integer.MAX_VALUE;

                    for (int i = info.alphabet.size(); i < nextCode; i++) {
                        if (codebook.containsKey(i)) {
                            int time = lastUsed.getOrDefault(i, 0);
                            if (time < minTime) {
                                minTime = time;
                                lruCode = i;
                            }
                        }
                    }

                    if (lruCode >= 0) {
                        String newEntry = prevString + entry.charAt(0);
                        codebook.put(lruCode, newEntry);
                        frequency.put(lruCode, 0);
                        lastUsed.put(lruCode, timestamp);
                    }
                } else if (info.policy.equals("lfu")) {
                    // Find LFU code (excluding alphabet)
                    int lfuCode = -1;
                    int minFreq = Integer.MAX_VALUE;

                    for (int i = info.alphabet.size(); i < nextCode; i++) {
                        if (codebook.containsKey(i)) {
                            int freq = frequency.getOrDefault(i, 0);
                            if (freq < minFreq) {
                                minFreq = freq;
                                lfuCode = i;
                            }
                        }
                    }

                    if (lfuCode >= 0) {
                        String newEntry = prevString + entry.charAt(0);
                        codebook.put(lfuCode, newEntry);
                        frequency.put(lfuCode, 0);
                        lastUsed.put(lfuCode, timestamp);
                    }
                }
                // else freeze - do nothing
            }

            prevString = entry;
            prevCode = code;
        }

        BinaryStdOut.close();
    }

    /**
     * Helper class to store header information
     */
    private static class HeaderInfo {
        int minW;
        int maxW;
        String policy;
        List<String> alphabet;
    }
}