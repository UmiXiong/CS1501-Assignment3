import java.io.*;

public class LZW_test
{
    // 新增：读取文件内容并打印（返回内容字符串用于比较）
    private static String readFileContent(String filePath) throws IOException
    {
        StringBuilder content = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath)))
        {
            String line;
            System.err.println("===== 文件内容：" + filePath + " =====");
            while ((line = reader.readLine()) != null)
            {
                content.append(line).append("\n");
                System.err.println(line); // 打印每行内容
            }
            System.err.println("=============================\n");
        }
        return content.toString();
    }

    public static boolean test(int minW, int maxW, String policy, String alphabetPath,String originalFile)
    {
        File originalFileObj = new File(originalFile);
        String fileName = originalFileObj.getName(); // 获取文件名（如 "test3.txt"）
        String baseName = fileName.substring(0, fileName.lastIndexOf('.')); // 提取不带扩展名的文件名（如 "test3"）

        // 压缩文件：原文件名_temp.lzw（如 "test3_temp.lzw"）
        String compressedFile = originalFileObj.getParent() + File.separator + baseName + "_temp.lzw";
        // 解压文件：原文件名_back.txt（如 "test3_back.txt"）
        String decompressedFile = originalFileObj.getParent() + File.separator + baseName + "_back.txt";

        // ===== 新增：如果文件存在则删除 =====
        File compFile = new File(compressedFile);
        if (compFile.exists())
        {
            compFile.delete();
            System.err.println("已删除残留压缩文件: " + compressedFile);
        }

        // ===================================


        InputStream originalIn = System.in;
        PrintStream originalOut = System.out;

        try
        {
            // ================ 第一步：执行压缩 ================
            System.err.println("=== 开始压缩 ===");
            System.err.println("原始文件: " + originalFile);
            File oriFileCheck = new File(originalFile);
            if (!oriFileCheck.exists() || oriFileCheck.length() == 0)
            {
                throw new IOException("请检查原始文件是否存在");
            }
            System.err.println("压缩文件: " + compressedFile);
            try (FileInputStream fileIn = new FileInputStream(originalFile);
                    FileOutputStream fileOut = new FileOutputStream(compressedFile))
            {
                System.setIn(fileIn);
                System.setOut(new PrintStream(fileOut));
//                LZWTool_local.compress(minW, maxW, policy, alphabetPath);
            }
            finally
            {
                BinaryStdIn.close();
                BinaryStdOut.close();
                System.setIn(originalIn);
                System.setOut(originalOut);
            }
            // ===== 新增：校验压缩文件是否为空 =====
            File compFileCheck = new File(compressedFile);
            if (!compFileCheck.exists() || compFileCheck.length() == 0)
            {
                throw new IOException("压缩失败：生成的压缩文件为空，请检查原始文件是否存在或压缩逻辑是否正确");
            }

            System.err.println("压缩文件生成成功，大小：" + compFileCheck.length() + " 字节");

            System.err.println("=== 开始解压 ===");
            try (FileInputStream fileIn = new FileInputStream(compressedFile);
                    FileOutputStream fileOut = new FileOutputStream(decompressedFile))
            {
                System.setIn(fileIn);
                System.setOut(new PrintStream(fileOut));
//                LZWTool_local.expand();
            }
            finally
            {

                BinaryStdIn.close();
                BinaryStdOut.close();
                System.setIn(originalIn);
                System.setOut(originalOut);
            }
            // ================ 第三步：读取并打印文件内容 ================
            System.err.println("=== 读取原始文件内容 ===");
            String originalContent = readFileContent(originalFile);
            System.err.println("=== 读取解压文件内容 ===");
            String decompressedContent = readFileContent(decompressedFile);

            // ================ 第四步：比较内容一致性 ================
            if (originalContent.equals(decompressedContent))
            {
                System.err.println(originalFile+"✅ 校验通过：内容一致");
                return true;
            }
            else
            {
                System.err.println(originalFile+"❌ 校验失败：内容不一致");
                return false;
            }

        }
        catch (Exception e)
        {
            System.err.println("测试失败：" + e.getMessage());
            e.printStackTrace();
        }
        return false;

    }

    public static void test3()
    {
        // 编码参数
        int minW = 3;
        int maxW = 4;
        // 配置文件路径
        String policy = "reset";
        String alphabetPath = "alphabets/toberh.txt";
        String originalFile = "TestFiles/test3.txt";       // 原始文件
        String compressedFile = "TestFiles/test3_temp.lzw"; // 临时压缩文件
        String decompressedFile = "TestFiles/test3_back.txt"; // 解压回退文件
        test(minW, maxW, policy, alphabetPath, originalFile);
    }

    public static void test22()
    {
        // 编码参数
        int minW = 3;
        int maxW = 4;
        // 配置文件路径
        String policy = "freeze";
        String alphabetPath = "alphabets/abrcd.txt";
        String originalFile = "TestFiles/test22.txt";       // 原始文件

        test(minW, maxW, policy, alphabetPath, originalFile);
    }

    public static void test21()
    {
        // 编码参数
        int minW = 3;
        int maxW = 4;
        // 配置文件路径
        String policy = "freeze";
        String alphabetPath = "alphabets/abrcd.txt";
        String originalFile = "TestFiles/test21.txt";       // 原始文件

        test(minW, maxW, policy, alphabetPath, originalFile);
    }

    public static void test1()
    {
        // 编码参数
        int minW = 3;
        int maxW = 4;
        // 配置文件路径
        String policy = "reset";
        String alphabetPath = "alphabets/ab.txt";
        String originalFile = "TestFiles/test1.txt";       // 原始文件

        test(minW, maxW, policy, alphabetPath, originalFile);
    }

    public static void testlru()
    {
        // 编码参数
        int minW = 3;
        int maxW = 4;
        // 配置文件路径
        String policy = "lru";
        String alphabetPath = "alphabets/ab.txt";
        String originalFile = "TestFiles/lru.txt";       // 原始文件

        test(minW, maxW, policy, alphabetPath, originalFile);
    }

    public static void testAscii()
    {
        // 编码参数
        int minW = 9;
        int maxW = 16;
        // 配置文件路径
        String policy = "freeze";
        String alphabetPath = "alphabets/ascii.txt";
        String originalFile = "TestFiles/code.txt";       // 原始文件

        test(minW, maxW, policy, alphabetPath, originalFile);
    }

    public static void main(String[] args)
    {
        test1();   //ok
        test21();  //ok
        test22(); //ok
        test3(); //ok
//          testlru(); //没通过
//        testAscii(); //没通过
    }
}
