public class Test {
    public static void main(String[] args) {
        String test = "{\"name\": \"Payment System \\S stripe\\\\stripe-php \\n \\u0026\"}";
        System.out.println("Original: " + test);
        String cleaned = test.replaceAll("\\\\(?![\\\"\\\\/bfnrtu])", "\\\\\\\\");
        System.out.println("Cleaned: " + cleaned);
    }
}
