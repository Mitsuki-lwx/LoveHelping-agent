import java.net.http.*; import java.net.URI; import java.time.Duration;
var c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
var req = HttpRequest.newBuilder(URI.create("https://api.typesafe.ai/v1/systemone"))
    .timeout(Duration.ofSeconds(30))
    .header("Content-Type","application/json")
    .POST(HttpRequest.BodyPublishers.ofString("{}")).build();
long t0 = System.nanoTime();
try { c.send(req, HttpResponse.BodyHandlers.ofString()); } catch (Exception e) { System.out.println("ERR " + e.getClass().getSimpleName()); }
System.out.println("JVM_COLD_MS=" + (System.nanoTime()-t0)/1_000_000);
/exit
