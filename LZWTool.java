import java.io.*;
import java.util.*;

/**
 * LZWTool - A configurable LZW compression and decompression tool
 * Supports variable codeword width, custom alphabets, and multiple eviction policies
 *
 * 修复：
 * - 在 header 中加入 codeCount（32-bit），压缩端先缓冲写出的 code+width，再写 header+codes，
 *   解压端依据 codeCount 精确读取编码个数，避免因位填充导致多读一个 code。
 */
public class LZWTool
{
    public static void main(String[] args)
    {
        String mode = null;
        int minW = 3;
        int maxW = 4;
        String policy = "reset";
        String alphabetPath = null;

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

        try
        {
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
    }

    private static List<String> readAlphabet(String path) throws IOException
    {
        List<String> alphabet = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        try (BufferedReader reader = new BufferedReader(new FileReader(path)))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line != null)
                {
                    line = line.trim();
                }
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
     * 写 header：minW(8) maxW(8) policy(8) alphabetSize(16) alphabetSymbols(每个1 byte) codeCount(32)
     */
    private static void writeHeader(int minW, int maxW, String policy, List<String> alphabet, int codeCount)
    {
        BinaryStdOut.write(minW, 8);
        BinaryStdOut.write(maxW, 8);

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

        BinaryStdOut.write(alphabet.size(), 16);

        for (String symbol : alphabet)
        {
            char ch = symbol.isEmpty() ? '\0' : symbol.charAt(0);
            BinaryStdOut.write(ch, 8);
        }

        // 写入实际 code 数（32-bit），解码端会精确读取这么多个 code
        BinaryStdOut.write(codeCount, 32);
    }

    private static HeaderInfo readHeader()
    {
        HeaderInfo info = new HeaderInfo();

        info.minW = BinaryStdIn.readInt(8);
        info.maxW = BinaryStdIn.readInt(8);

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
            default:
                info.policy = "reset";
                break;
        }

        int alphabetSize = BinaryStdIn.readInt(16);
        info.alphabet = new ArrayList<>();
        for (int i = 0; i < alphabetSize; i++)
        {
            char c = BinaryStdIn.readChar(8);
            info.alphabet.add(String.valueOf(c));
        }

        // 读取 codeCount（32-bit）
        info.codeCount = BinaryStdIn.readInt(32);

        return info;
    }

    /**
     * Compress：不直接写 header，而是在内存中记录写出的 code 列表与对应写入时的宽度，
     * 完成后写 header（包含 codeCount）并按记录写入所有 code（使用各自宽度）。
     */
    private static void compress(int minW, int maxW, String policy, String alphabetPath) throws IOException
    {
        List<String> alphabet = readAlphabet(alphabetPath);

        int W = minW;
        int maxCodeLimit = (1 << maxW);

        Map<String, Integer> codebook = new HashMap<>();
        Map<Integer, String> reverseCodebook = new HashMap<>();
        int nextCode = 0;

        for (String symbol : alphabet)
        {
            codebook.put(symbol, nextCode);
            reverseCodebook.put(nextCode, symbol);
            nextCode++;
        }

        Map<Integer, Integer> frequency = new HashMap<>();
        Map<Integer, Integer> lastUsed = new HashMap<>();
        int timestamp = 0;

        for (int i = 0; i < alphabet.size(); i++)
        {
            frequency.put(i, 0);
            lastUsed.put(i, 0);
        }

        StringBuilder current = new StringBuilder();

        // 缓冲写出的 codes 与相应的 widths（W）
        List<Integer> codes = new ArrayList<>();
        List<Integer> widths = new ArrayList<>();

        while (!BinaryStdIn.isEmpty())
        {
            char c = BinaryStdIn.readChar(8);
            StringBuilder next = new StringBuilder(current).append(c);
            String nextStr = next.toString();

            if (codebook.containsKey(nextStr))
            {
                current = next;
            }
            else
            {
                if (current.length() > 0)
                {
                    String currentStr = current.toString();
                    Integer code = codebook.get(currentStr);
                    if (code != null)
                    {
                        // 记录 code 与当时的宽度（随后再一次性输出）
                        codes.add(code);
                        widths.add(W);
                        frequency.put(code, frequency.getOrDefault(code, 0) + 1);
                        lastUsed.put(code, timestamp++);
                    }
                }

                if (nextCode < maxCodeLimit)
                {
                    if (nextCode == (1 << W) && W < maxW)
                    {
                        W++;
                    }

                    codebook.put(nextStr, nextCode);
                    reverseCodebook.put(nextCode, nextStr);
                    frequency.put(nextCode, 0);
                    lastUsed.put(nextCode, timestamp);
                    nextCode++;
                }
                else
                {
                    // Codebook full: 按策略替换或 reset（与原逻辑一致）
                    if (policy.equals("reset"))
                    {
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

                        if (nextCode == (1 << W) && W < maxW)
                        {
                            W++;
                        }

                        codebook.put(nextStr, nextCode);
                        reverseCodebook.put(nextCode, nextStr);
                        frequency.put(nextCode, 0);
                        lastUsed.put(nextCode, timestamp);
                        nextCode++;
                    }
                    else if (policy.equals("lru"))
                    {
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
                            if (oldPattern != null)
                            {
                                codebook.remove(oldPattern);
                            }

                            codebook.put(nextStr, lruCode);
                            reverseCodebook.put(lruCode, nextStr);
                            frequency.put(lruCode, 0);
                            lastUsed.put(lruCode, timestamp);
                        }
                    }
                    else if (policy.equals("lfu"))
                    {
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

                            codebook.put(nextStr, lfuCode);
                            reverseCodebook.put(lfuCode, nextStr);
                            frequency.put(lfuCode, 0);
                            lastUsed.put(lfuCode, timestamp);
                        }
                    }
                    // freeze: do nothing
                }

                current = new StringBuilder().append(c);
            }
        }

        // 最后剩余的 current 输出（缓冲）
        if (current.length() > 0)
        {
            Integer code = codebook.get(current.toString());
            if (code != null)
            {
                codes.add(code);
                widths.add(W);
            }
        }

        // 写 header（现在可以写 codeCount）
        int codeCount = codes.size();
        writeHeader(minW, maxW, policy, alphabet, codeCount);

        // 按记录的 widths 将 codes 写入输出流
        for (int i = 0; i < codeCount; i++)
        {
            BinaryStdOut.write(codes.get(i), widths.get(i));
        }

        BinaryStdOut.close();
    }

    /**
     * Expand：依据 header 的 codeCount 精确读取 N 个 code（读第1个，然后读剩余 count-1 次）。
     * 读取顺序中仍保持与 encoder 一致的增宽、添加字典与替换时机。
     */
    private static void expand() throws IOException
    {
        HeaderInfo info = readHeader();

        int W = info.minW;
        int maxCodeLimit = (1 << info.maxW);

        Map<Integer, String> codebook = new HashMap<>();
        int nextCode = 0;

        for (String symbol : info.alphabet)
        {
            codebook.put(nextCode++, symbol);
        }

        Map<Integer, Integer> frequency = new HashMap<>();
        Map<Integer, Integer> lastUsed = new HashMap<>();
        int timestamp = 0;

        for (int i = 0; i < info.alphabet.size(); i++)
        {
            frequency.put(i, 0);
            lastUsed.put(i, 0);
        }

        // 如果初始字典大小已经填满当前 W 的容量，和 encoder 保持一致地增加 W
        if (nextCode == (1 << W) && W < info.maxW)
        {
            W++;
        }

        int totalCodes = info.codeCount;
        if (totalCodes <= 0)
        {
            BinaryStdOut.close();
            return;
        }

        // 读取第一个 code（必须存在）
        int prevCode;
        try
        {
            prevCode = BinaryStdIn.readInt(W);
        }
        catch (NoSuchElementException e)
        {
            BinaryStdOut.close();
            return;
        }

        String prevString = codebook.get(prevCode);
        if (prevString == null)
        {
            BinaryStdOut.close();
            return;
        }

        BinaryStdOut.write(prevString);
        frequency.put(prevCode, frequency.getOrDefault(prevCode, 0) + 1);
        lastUsed.put(prevCode, timestamp++);

        // 已读取的 code 数 = 1
        int readSoFar = 1;

        while (readSoFar < totalCodes)
        {
            // 在读取下一个 code 之前，保持与 encoder 一致地检查是否需要增加 W
            if (nextCode == (1 << W) && W < info.maxW)
            {
                W++;
            }

            int code;
            try
            {
                code = BinaryStdIn.readInt(W);
            }
            catch (NoSuchElementException e)
            {
                // 流异常终止（不应出现，因为 header 给出了确切的 codeCount），但我们优雅地结束
                break;
            }

            readSoFar++;

            String entry;
            if (codebook.containsKey(code))
            {
                entry = codebook.get(code);
            }
            else if (code == nextCode)
            {
                // KwKwK 特殊情况
                entry = prevString + prevString.charAt(0);
            }
            else
            {
                throw new RuntimeException("Invalid code during expand: " + code);
            }

            BinaryStdOut.write(entry);
            frequency.put(code, frequency.getOrDefault(code, 0) + 1);
            lastUsed.put(code, timestamp++);

            // 在添加新 entry 前，先判断是否还有空间或是否需按策略替换（镜像 encoder 行为）
            if (nextCode < maxCodeLimit)
            {
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
            else
            {
                // 字典已满，按策略处理
                if (info.policy.equals("reset"))
                {
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
                // freeze: do nothing
            }

            prevString = entry;
            //prevCode = code; // not actually required further, kept for clarity
        }

        BinaryStdOut.close();
    }

    private static class HeaderInfo
    {
        int minW;
        int maxW;
        String policy;
        List<String> alphabet;
        int codeCount;
    }
}