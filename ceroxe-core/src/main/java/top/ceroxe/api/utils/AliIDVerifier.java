package top.ceroxe.api.utils;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

public class AliIDVerifier {

    // 阿里云市场 API 地址
    private static final String HOST = "https://kzidcardv1.market.alicloudapi.com";
    private static final String PATH = "/api-mall/api/id_card/check";

    // 全局共享一个 HttpClient 实例，以利用连接池优化性能
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)) // 设置连接超时
            .build();

    /**
     * 校验身份证信息
     *
     * @param appCode 你的阿里云 AppCode
     * @param name    真实姓名
     * @param idCard  身份证号
     * @return API 返回的 JSON 字符串
     * @throws IOException          网络异常
     * @throws InterruptedException 线程中断异常
     */
    public static String verify(String appCode, String name, String idCard) throws IOException, InterruptedException {
        // 1. 构建表单数据 (application/x-www-form-urlencoded)
        Map<String, String> params = new HashMap<>();
        params.put("name", name);
        params.put("idcard", idCard);

        String formData = params.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));

        // 2. 构建请求
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(HOST + PATH))
                .header("Authorization", "APPCODE " + appCode)
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(formData))
                .timeout(Duration.ofSeconds(10)) // 设置读取超时
                .build();

        // 3. 发送请求并获取响应
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

        // 这里可以根据需要判断 response.statusCode() != 200 抛出异常
        return response.body();
    }

    // 辅助方法：URL编码
    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}