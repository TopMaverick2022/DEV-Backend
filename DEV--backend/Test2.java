public class Test2 {
    public static String sanitizeJson(String json) {
        if (json == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                if (i + 1 < json.length()) {
                    char next = json.charAt(i + 1);
                    if (next == '"' || next == '\\' || next == '/' || next == 'b' || next == 'f' || next == 'n' || next == 'r' || next == 't' || next == 'u') {
                        sb.append(c);
                        sb.append(next);
                        i++;
                    } else {
                        sb.append("\\\\");
                    }
                } else {
                    sb.append("\\\\");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
    public static void main(String[] args) {
        String test = "{\"name\": \"Payment System \\S stripe\\\\stripe-php \\n \\u0026\"}";
        System.out.println("Original: " + test);
        System.out.println("Cleaned: " + sanitizeJson(test));
    }
}
