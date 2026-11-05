package android.net;
public class Uri { public static String encode(String s) { try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); } catch (Exception e) { return s; } } }
