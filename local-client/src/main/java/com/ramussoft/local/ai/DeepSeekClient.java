package com.ramussoft.local.ai;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;

/** DeepSeek's official OpenAI-compatible Chat Completions client. */
public final class DeepSeekClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    public String analyze(String prompt, ModelSnapshot snapshot, String apiKey, String model) throws Exception {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("DeepSeek API key is empty");
        String system = "You analyze Ramus IDEF0/DFD model snapshots. Return concise findings only. Do not invent IDs or apply changes.";
        String user = prompt + "\n\nSnapshot:\n" + snapshot.content();
        String body = "{\"model\":\"" + esc(model) + "\",\"messages\":[{\"role\":\"system\",\"content\":\"" + esc(system)
                + "\"},{\"role\":\"user\",\"content\":\"" + esc(user) + "\"}],\"stream\":false}";
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.deepseek.com/chat/completions"))
                .timeout(Duration.ofSeconds(90)).header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("DeepSeek HTTP " + response.statusCode() + ": " + response.body());
        return content(response.body());
    }
    public String generatePlan(String prompt, ModelSnapshot snapshot, String apiKey, String model) throws Exception {
        String system = "Return JSON only, without markdown. Schema: {\"operations\":[{\"tool\":\"create_element\",\"qualifier\":\"Работы\",\"name\":\"...\"},{\"tool\":\"create_stream\",\"qualifier\":\"Потоки\",\"name\":\"...\",\"source\":\"exact function name\",\"target\":\"exact function name\",\"icom\":\"input|control|mechanism|output\",\"style\":\"solid|dashed|dotted|lightning\",\"color\":\"#RRGGBB\"}]}. Use only these two tools. Create hierarchies in readable levels. Route feedback around blocks, not through them. Choose styles sparingly: control dashed, normal flow solid; use lightning only when the user asks for it. Return a color only as #RRGGBB. Create all functions before streams. Do not invent existing IDs.";
        return analyze(system + "\nTask:\n" + prompt, snapshot, apiKey, model);
    }
    static String esc(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"); }
    static String content(String json) {
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile("\\\"content\\\"\\s*:\\s*\\\"").matcher(json);
        if (!marker.find()) throw new IllegalArgumentException("Ответ модели не содержит текст сообщения");
        int start = marker.end(); StringBuilder out = new StringBuilder(); boolean escaped = false;
        for (int i = start; i < json.length(); i++) { char c = json.charAt(i);
            if (escaped) {
                if (c == 'u' && i + 4 < json.length()) {
                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); i += 4;
                } else out.append(c == 'n' ? '\n' : c == 'r' ? '\r' : c == 't' ? '\t' : c);
                escaped = false;
            }
            else if (c == '\\') escaped = true; else if (c == '"') break; else out.append(c);
        } return out.toString();
    }
}
