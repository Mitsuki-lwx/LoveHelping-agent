import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;

/** 量 JEV 真实调用延迟分布（冷/热 + 失败率）。用于判定 timeout 该给多少。 */
public class JevLat {
    public static void main(String[] args) throws Exception {
        String key = System.getenv("JEV_API_KEY");
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 20;
        String body = "{\"state\":{\"user_text\":\"今天我和女朋友吵了一架，很难过\"},"
                + "\"model\":\"jev-latest\",\"questions\":{\"mood\":{\"type\":\"score\","
                + "\"instructions\":\"判断情绪\",\"criteria\":[\"很糟\",\"低落\",\"平静\",\"好一些\",\"很好\"]}}}";
        HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        ArrayList<Long> lat = new ArrayList<>();
        int fail = 0, code = 0;
        for (int i = 0; i < n; i++) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.typesafe.ai/v1/systemone"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + key)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            long t0 = System.nanoTime();
            try {
                HttpResponse<String> r = c.send(req, HttpResponse.BodyHandlers.ofString());
                code = r.statusCode();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                lat.add(ms);
                System.out.println("  #" + i + "  " + ms + "ms  http=" + code);
            } catch (Exception e) {
                fail++;
                System.out.println("  #" + i + "  FAIL " + e.getClass().getSimpleName());
            }
        }
        Collections.sort(lat);
        System.out.println("STATUS=" + code + "  n=" + lat.size() + "  fail=" + fail);
        if (!lat.isEmpty()) {
            System.out.println("LAT_MS min=" + lat.get(0) + " p50=" + lat.get(lat.size() / 2)
                    + " p95=" + lat.get(Math.min(lat.size() - 1, (int) (lat.size() * 0.95)))
                    + " max=" + lat.get(lat.size() - 1));
        }
    }
}
