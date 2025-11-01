import java.io.*;
import java.util.*;

/**
 * LZWTool - A configurable LZW compression and decompression tool Supports variable codeword width, custom alphabets,
 * and multiple eviction policies
 */
public class LZWTool
{

    //    private static boolean DEBUG = false;
    private static void printCodebook(Map<?, ?> codebook, String name) {
        System.err.println("\n===== " + name + " Codebook Contents =====");
        if (codebook.isEmpty()) {
            System.err.println("Codebook is empty");
            return;
        }
        for (Map.Entry<?, ?> entry : codebook.entrySet()) {
            System.err.printf("Key: %-5s Value: %s%n", entry.getKey(), entry.getValue());
        }
        System.err.println("=============================\n");
    }
    public static void main(String[] args)
    {
        // Parse command-line arguments
//        String mode = "compress";
//        String inputFile ="TestFiles/test2.txt";
//        String outputFile ="TestFiles/test2_output.lzw";

        String mode ="expand";
        String inputFile ="TestFiles/test2_output.lzw";
        String outputFile ="TestFiles/test2_back.txt";

        String alphabetPath = "alphabets/tobeornot.txt";

//        String mode=null;
        int minW = 3;
        int maxW = 4;
        String policy = "freeze";
//        String alphabetPath=null;

        for (int i = 0; i < args.length; i++)
        {
            switch (args[i])
            {
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
        if (mode == null)
        {
            System.err.println("Error: --mode is required");
            System.exit(1);
        }

        if (minW > maxW)
        {
            System.err.println("Error: minW must be <= maxW");
            System.exit(1);
        }


        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;

        // Execute compression or expansion
        try
        {

            FileInputStream fileIn = new FileInputStream(inputFile);
            System.setIn(fileIn);

            File file = new File(outputFile);
            //文件存在删除，创建新文件
            if (file.exists()) {
                file.delete();
            }
            file.createNewFile();

            FileOutputStream fileOut = new FileOutputStream(outputFile);
            PrintStream printOut = new PrintStream(fileOut);
            System.setOut(printOut);

            if (mode.equals("compress"))
            {
                if (alphabetPath == null)
                {
                    System.err.println("Error: --alphabet is required for compression");
                    System.exit(1);
                }
                compress(minW, maxW, policy, alphabetPath);
            }
            else if (mode.equals("expand"))
            {
                expand();
            }
            else
            {
                System.err.println("Error: mode must be 'compress' or 'expand'");
                System.exit(1);
            }
        }
        catch (Exception e)
        {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
        finally
        {
            // 4. 恢复原始的输入流和输出流（避免影响后续操作）
            try {
                System.in.close(); // 关闭文件输入流
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.out.close(); // 关闭文件输出流
            System.setIn(originalIn); // 恢复控制台输入
            System.setOut(originalOut); // 恢复控制台输出
        }
    }

    /**
     * Read alphabet from file
     */
    private static List<String> readAlphabet(String path) throws IOException
    {
        List<String> alphabet = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (!line.isEmpty() && !seen.contains(line))
                {
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
    private static void writeHeader(int minW, int maxW, String policy, List<String> alphabet)
    {
        // Write minW (1 byte)
        BinaryStdOut.write(minW, 8);

        // Write maxW (1 byte)
        BinaryStdOut.write(maxW, 8);

        // Write policy as integer (1 byte)
        int policyCode = 0;
        switch (policy)
        {
            case "freeze":
                policyCode = 0;
                break;
            case "reset":
                policyCode = 1;
                break;
            case "lru":
                policyCode = 2;
                break;
            case "lfu":
                policyCode = 3;
                break;
        }
        BinaryStdOut.write(policyCode, 8);

        // Write alphabet size (2 bytes)
        BinaryStdOut.write(alphabet.size(), 16);

        // Write each alphabet symbol (1 byte per symbol)
        for (String symbol : alphabet)
        {
            BinaryStdOut.write(symbol.charAt(0), 8);
        }
    }

    /**
     * Read header from compressed file
     */
    private static HeaderInfo readHeader()
    {
        HeaderInfo info = new HeaderInfo();

        // Read minW
        info.minW = BinaryStdIn.readInt(8);

        // Read maxW
        info.maxW = BinaryStdIn.readInt(8);

        // Read policy
        int policyCode = BinaryStdIn.readInt(8);
        switch (policyCode)
        {
            case 0:
                info.policy = "freeze";
                break;
            case 1:
                info.policy = "reset";
                break;
            case 2:
                info.policy = "lru";
                break;
            case 3:
                info.policy = "lfu";
                break;
        }

        // Read alphabet size
        int alphabetSize = BinaryStdIn.readInt(16);

        // Read alphabet
        info.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++)
        {
            char c = BinaryStdIn.readChar(8);
            info.alphabet.add(String.valueOf(c));
        }

        return info;
    }

    /**
     * Compress input using LZW algorithm
     */
    private static void compress(int minW, int maxW, String policy, String alphabetPath) throws IOException
    {
        // Read alphabet from file
        List<String> alphabet = readAlphabet(alphabetPath);

        // Write header
        writeHeader(minW, maxW, policy, alphabet);

        // Initialize compression state
        int W = minW;
        int maxCodeLimit = (1 << maxW);

        // Build initial codebook: use HashMap for pattern->code so we can remove old patterns when evicting
        Map<String, Integer> codebook = new HashMap<>();
        Map<Integer, String> reverseCodebook = new HashMap<>();
        int nextCode = 0;

        for (String symbol : alphabet)
        {
            codebook.put(symbol, nextCode);
            reverseCodebook.put(nextCode, symbol);
            nextCode++;
        }

        // 打印初始Codebook
        printCodebook(reverseCodebook, "初始化时");

        // Tracking for eviction policies
        Map<Integer, Integer> frequency = new HashMap<>();
        Map<Integer, Integer> lastUsed = new HashMap<>();
        int timestamp = 0;

        for (int i = 0; i < alphabet.size(); i++)
        {
            frequency.put(i, 0);
            lastUsed.put(i, 0);
        }

        // Process input
        StringBuilder current = new StringBuilder();

        StringBuffer sb = new StringBuffer();

        while (!BinaryStdIn.isEmpty())
        {
            char c = BinaryStdIn.readChar(8);
            StringBuilder next = new StringBuilder(current).append(c);
            String nextStr = next.toString();

            System.err.println("当前字符: " + current);
            System.err.println("下一个字符: " + next);

            if (codebook.containsKey(nextStr))
            {
                current = next;
            }
            else
            {
                // Output code for current
                if (current.length() > 0)
                {
                    String currentStr = current.toString();
                    Integer code = codebook.get(currentStr);
                    if (code != null)
                    {
                        sb.append(code);
                        System.err.println("编码:"+sb.toString());
                        BinaryStdOut.write(code, W);
                        frequency.put(code, frequency.getOrDefault(code, 0) + 1);
                        lastUsed.put(code, timestamp++);
                    }
                }

                // Try to add new pattern
                if (nextCode < maxCodeLimit)
                {
                    // Increase width if needed BEFORE adding the new code
                    if (nextCode == (1 << W) && W < maxW)
                    {
                        W++;
                    }

                    codebook.put(nextStr, nextCode);
                    reverseCodebook.put(nextCode, nextStr);
                    frequency.put(nextCode, 0);
                    lastUsed.put(nextCode, timestamp);
                    nextCode++;

                    // 打印更新后的Codebook
                    printCodebook(reverseCodebook, "添加新码表后");
                }
                else
                {
                    // Codebook full - apply eviction policy
                    if (policy.equals("reset"))
                    {
                        // Reset to alphabet only
                        codebook = new HashMap<>();
                        reverseCodebook.clear();
                        frequency.clear();
                        lastUsed.clear();
                        nextCode = 0;

                        for (String symbol : alphabet)
                        {
                            codebook.put(symbol, nextCode);
                            reverseCodebook.put(nextCode, symbol);
                            frequency.put(nextCode, 0);
                            lastUsed.put(nextCode, timestamp);
                            nextCode++;
                        }

                        W = minW;

                        // Add the new pattern
                        if (nextCode == (1 << W) && W < maxW)
                        {
                            W++;
                        }

                        codebook.put(nextStr, nextCode);
                        reverseCodebook.put(nextCode, nextStr);
                        frequency.put(nextCode, 0);
                        lastUsed.put(nextCode, timestamp);
                        nextCode++;

                        printCodebook(reverseCodebook, "reset码表后");
                    }
                    else if (policy.equals("lru"))
                    {
                        // Find LRU code (excluding alphabet)
                        int lruCode = -1;
                        int minTime = Integer.MAX_VALUE;

                        for (int i = alphabet.size(); i < nextCode; i++)
                        {
                            if (reverseCodebook.containsKey(i))
                            {
                                int time = lastUsed.getOrDefault(i, 0);
                                if (time < minTime)
                                {
                                    minTime = time;
                                    lruCode = i;
                                }
                            }
                        }

                        if (lruCode >= 0)
                        {
                            String oldPattern = reverseCodebook.get(lruCode);
                            // remove old pattern from pattern->code map to keep consistency
                            if (oldPattern != null)
                            {
                                codebook.remove(oldPattern);
                            }

                            // Replace with new pattern
                            codebook.put(nextStr, lruCode);
                            reverseCodebook.put(lruCode, nextStr);
                            frequency.put(lruCode, 0);
                            lastUsed.put(lruCode, timestamp);
                        }
                        printCodebook(reverseCodebook, "lru更新码表后");
                    }
                    else if (policy.equals("lfu"))
                    {
                        // Find LFU code (excluding alphabet)
                        int lfuCode = -1;
                        int minFreq = Integer.MAX_VALUE;

                        for (int i = alphabet.size(); i < nextCode; i++)
                        {
                            if (reverseCodebook.containsKey(i))
                            {
                                int freq = frequency.getOrDefault(i, 0);
                                if (freq < minFreq)
                                {
                                    minFreq = freq;
                                    lfuCode = i;
                                }
                            }
                        }

                        if (lfuCode >= 0)
                        {
                            String oldPattern = reverseCodebook.get(lfuCode);
                            if (oldPattern != null)
                            {
                                codebook.remove(oldPattern);
                            }

                            // Replace with new pattern
                            codebook.put(nextStr, lfuCode);
                            reverseCodebook.put(lfuCode, nextStr);
                            frequency.put(lfuCode, 0);
                            lastUsed.put(lfuCode, timestamp);
                        }
                        printCodebook(reverseCodebook, "lfu更新码表后");
                    }
                    // else freeze - do nothing
                }

                current = new StringBuilder().append(c);
            }
        }

        // Output final code
        if (current.length() > 0)
        {
            Integer code = codebook.get(current.toString());
            if (code != null)
            {
                BinaryStdOut.write(code, W);
                sb.append(code);
            }
        }
        System.err.println("编码:"+sb.toString());
        // Write stop code (use maximum possible value for current width as EOF marker)
        int stopCode = (1 << W) - 1;
        BinaryStdOut.write(stopCode, W);

        BinaryStdOut.close();
    }

    /**
     * Expand compressed input
     */
    private static void expand() throws IOException
    {
        // Read header
        HeaderInfo info = readHeader();

        int W = info.minW;
        int maxCodeLimit = (1 << info.maxW);

        // Build initial codebook
        Map<Integer, String> codebook = new HashMap<>();
        int nextCode = 0;

        for (String symbol : info.alphabet)
        {
            codebook.put(nextCode++, symbol);
        }
        // 打印初始Codebook
        printCodebook(codebook, "初始化时");

        // Tracking for eviction policies
        Map<Integer, Integer> frequency = new HashMap<>();
        Map<Integer, Integer> lastUsed = new HashMap<>();
        int timestamp = 0;

        for (int i = 0; i < info.alphabet.size(); i++)
        {
            frequency.put(i, 0);
            lastUsed.put(i, 0);
        }

        // Possibly increase width BEFORE reading first code if initial codebook size reaches the current width capacity
        if (nextCode == (1 << W) && W < info.maxW)
        {
            W++;
        }
        System.err.println("字典下一code:"+nextCode+"当前码长:"+W);

        // Read first code
        if (BinaryStdIn.isEmpty())
        {
            BinaryStdOut.close();
            return;
        }
        StringBuffer sbCode = new StringBuffer();
        StringBuffer sbContent = new StringBuffer();

        int prevCode = BinaryStdIn.readInt(W);
        sbCode.append(prevCode);
        System.err.println("编码:"+sbCode);
        String prevString = codebook.get(prevCode);

        if (prevString == null)
        {
            BinaryStdOut.close();
            return;
        }
        sbContent.append(prevString);
        System.err.println("内容:"+sbContent);

        BinaryStdOut.write(prevString);
        frequency.put(prevCode, frequency.getOrDefault(prevCode, 0) + 1);
        lastUsed.put(prevCode, timestamp++);

        // Process remaining codes
        while (!BinaryStdIn.isEmpty())
        {
            // Possibly increase width BEFORE reading the next code so decoder stays in sync with encoder
            if (nextCode == (1 << W) && W < info.maxW)
            {
                W++;
            }
            System.err.println("字典下一code:"+nextCode+"当前码长:"+W);
            if (nextCode == (1 << W) && W == info.maxW)
            {
                if (info.policy.equals("reset"))
                {
                    W = info.minW;
                    System.err.println("字典下一code超出最大范围重新设置码长:"+W);
                }
                if (info.policy.equals("freeze"))
                {
                    System.err.println("保持字典不动"+ W);
                }
            }


            int code;
            try
            {
                code = BinaryStdIn.readInt(W);
            }
            catch (NoSuchElementException e)
            {

                // End of stream reached
                break;
            }
            sbCode.append(code);
            System.err.println("编码:"+sbCode);

            // Check for stop code


            int stopCode = (1 << W) - 1;
            if (code == stopCode)
            {
                break;
            }

            String entry;

            if (codebook.containsKey(code))
            {
                entry = codebook.get(code);
            }
            else if (code == nextCode)
            {
                // Special case: code not yet in codebook
                entry = prevString + prevString.charAt(0);
            }
            else
            {
                throw new RuntimeException("Invalid code: " + code);
            }
            sbContent.append(entry);
            System.err.println("内容:"+sbContent);

            BinaryStdOut.write(entry);
            frequency.put(code, frequency.getOrDefault(code, 0) + 1);
            lastUsed.put(code, timestamp++);

            // Add new entry to codebook
            if (nextCode < maxCodeLimit)
            {
                // Check if we need to increase width BEFORE adding
                if (nextCode == (1 << W) && W < info.maxW)
                {
                    W++;
                }

                String newEntry = prevString + entry.charAt(0);
                codebook.put(nextCode, newEntry);
                frequency.put(nextCode, 0);
                lastUsed.put(nextCode, timestamp);
                nextCode++;
                printCodebook(codebook, "添加新码表后");
            }
            else
            {
                // Codebook full - apply eviction policy
                if (info.policy.equals("reset"))
                {
                    // Reset to alphabet only
                    codebook.clear();
                    nextCode = 0;

                    for (String symbol : info.alphabet)
                    {
                        codebook.put(nextCode, symbol);
                        frequency.put(nextCode, 0);
                        lastUsed.put(nextCode, timestamp);
                        nextCode++;
                    }

                    W = info.minW;

                    // Add the new pattern
                    if (nextCode == (1 << W) && W < info.maxW)
                    {
                        W++;
                    }

                    String newEntry = prevString + entry.charAt(0);
                    codebook.put(nextCode, newEntry);
                    frequency.put(nextCode, 0);
                    lastUsed.put(nextCode, timestamp);
                    nextCode++;
                }
                else if (info.policy.equals("lru"))
                {
                    // Find LRU code (excluding alphabet)
                    int lruCode = -1;
                    int minTime = Integer.MAX_VALUE;

                    for (int i = info.alphabet.size(); i < nextCode; i++)
                    {
                        if (codebook.containsKey(i))
                        {
                            int time = lastUsed.getOrDefault(i, 0);
                            if (time < minTime)
                            {
                                minTime = time;
                                lruCode = i;
                            }
                        }
                    }

                    if (lruCode >= 0)
                    {
                        String newEntry = prevString + entry.charAt(0);
                        codebook.put(lruCode, newEntry);
                        frequency.put(lruCode, 0);
                        lastUsed.put(lruCode, timestamp);
                    }
                }
                else if (info.policy.equals("lfu"))
                {
                    // Find LFU code (excluding alphabet)
                    int lfuCode = -1;
                    int minFreq = Integer.MAX_VALUE;

                    for (int i = info.alphabet.size(); i < nextCode; i++)
                    {
                        if (codebook.containsKey(i))
                        {
                            int freq = frequency.getOrDefault(i, 0);
                            if (freq < minFreq)
                            {
                                minFreq = freq;
                                lfuCode = i;
                            }
                        }
                    }

                    if (lfuCode >= 0)
                    {
                        String newEntry = prevString + entry.charAt(0);
                        codebook.put(lfuCode, newEntry);
                        frequency.put(lfuCode, 0);
                        lastUsed.put(lfuCode, timestamp);
                    }
                }
                // else freeze - do nothing
                else{

                }
            }

            prevString = entry;
            prevCode = code;
        }

        BinaryStdOut.close();
    }

    /**
     * Helper class to store header information
     */
    private static class HeaderInfo
    {
        int minW;

        int maxW;

        String policy;

        List<String> alphabet;
    }
}