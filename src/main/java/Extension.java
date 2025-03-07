import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;

import burp.api.montoya.core.ToolType;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.logging.Logging;
import burp.api.montoya.ui.contextmenu.ContextMenuEvent;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ai.chat.PromptException;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

import static java.util.Collections.emptyList;
import static java.util.concurrent.Executors.newFixedThreadPool;

public class Extension implements BurpExtension {
    public final String PreferencePrefix = "repeater_renamer_";
    public final String PreferenceAPIKEY = PreferencePrefix + "APIKEY";
    public final String PreferenceAPIEndpoint = PreferencePrefix + "APIEndpoint";
    public final String PreferenceAPIModel = PreferencePrefix + "Model";


    public MontoyaApi api;
    public Logging logging;
    public ExecutorService executorService;
    public Integer retryTime = 5;
    public String APIKEY = "";
    public String model = "";
    public String APIEndpoint = "";



    @Override
    public void initialize(MontoyaApi api) {
        this.api = api;
        this.logging = api.logging();
        this.executorService = newFixedThreadPool(5);
        logging.logToOutput("Repeater AI Renamer extension initialized.");

        this.APIKEY = api.persistence().preferences().getString(PreferenceAPIKEY);
        this.APIEndpoint = api.persistence().preferences().getString(PreferenceAPIEndpoint);
        this.model = api.persistence().preferences().getString(PreferenceAPIModel);

        api.userInterface().registerContextMenuItemsProvider(new RepeaterContextMenuProvider());

        api.extension().registerUnloadingHandler(() -> {
            executorService.shutdown();
            logging.logToOutput("Repeater AI Renamer extension unloaded.");
        });

        api.userInterface().registerSuiteTab("Repeater Renamer", createSuitePanel());
    }


    private class RepeaterContextMenuProvider implements ContextMenuItemsProvider {
        @Override
        public List<Component> provideMenuItems(ContextMenuEvent event) {
            if (!event.isFromTool(ToolType.PROXY) && !event.isFromTool(ToolType.REPEATER) && !event.isFromTool(ToolType.TARGET)) {
                return emptyList();
            }
            JMenuItem menuItem = new JMenuItem("Send to Repeater (with renamer)");
            menuItem.addActionListener(e -> {
                HttpRequestResponse requestResponse = event.messageEditorRequestResponse().get().requestResponse();
                if (requestResponse != null) {
                    // Generate name using AI
                    executorService.submit(() -> {
                        try {
                            String generatedName = generateNameFromRequest(requestResponse);
                            api.repeater().sendToRepeater(requestResponse.request(), generatedName);
                            logging.logToOutput("Request sent to Repeater with name: " + generatedName);
                        } catch (Exception ex) {
                            logging.logToError("Error sending request to Repeater: " + ex.getMessage());
                            api.repeater().sendToRepeater(requestResponse.request());
                        }
                    });
                }
            });
            return Collections.singletonList(menuItem);
        }
    }

    private String generateNameFromRequest(HttpRequestResponse httpRequestResponse) throws PromptException {
        String newRequest = String.format(
                """
                    URL: %s
                    BODY: %s
                """,
                this.sanitizeUrl(httpRequestResponse.request().url()),
                this.sanitizeBody(httpRequestResponse.request().bodyToString(), httpRequestResponse.request().headerValue("Content-Type"))
        );
        String newResponse = "";
        if(httpRequestResponse.response() != null) {
            newResponse = String.format(
                    """
                        Content-Type: %s
                        BODY: %s
                    """,
                    httpRequestResponse.response().headerValue("Content-Type"),
                    this.sanitizeBody(httpRequestResponse.response().bodyToString(), httpRequestResponse.response().headerValue("Content-Type"))
            );
        }

        String prompt = String.format(
                """
                        Generate a concise name for this HTTP request (max 5 words, use Chinese). \s
                        Request:\s
                        %s
                        
                        
                        Response:\s
                        %s""",
                newRequest,
                newResponse
        );
        this.logging.logToOutput("prompt:\n" + prompt + "\n");

        // 构建请求JSON
        JSONObject requestBody = new JSONObject();
        requestBody.put("model", this.model);
        requestBody.put("messages", new JSONObject[]{
                new JSONObject().put("role", "user").put("content", prompt)
        });

        try {
            HttpClient client = HttpClient.newHttpClient();

            HttpRequest httpRequest = HttpRequest.newBuilder()
                    .uri(URI.create(APIEndpoint))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + this.APIKEY)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody.toString()))
                    .timeout(Duration.ofSeconds(15))
                    .build();
            int retry = 0;
            HttpResponse<String> response = null;
            while (true) {
                response = client.send(
                        httpRequest,
                        HttpResponse.BodyHandlers.ofString()
                );
                this.logging.logToOutput(response.toString());

                if (response.statusCode() != 200) {
                    String errorMsg = new JSONObject(response.body())
                            .getJSONObject("error")
                            .getString("message");
                    retry ++;
                    if (retry >= retryTime) {
                        throw new PromptException("API Error: " + errorMsg);
                    }
                } else {
                    break;
                }
            }
            JSONObject jsonResponse = new JSONObject(response.body());
            String content = jsonResponse.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content")
                    .trim();

            return content.replaceAll("[\"\\d+\\.]", "");


        } catch (IOException | InterruptedException e) {
            throw new PromptException("Network error: " + e.getMessage());
        } catch (JSONException e) {
            throw new PromptException("JSON parsing failed: " + e.getMessage());
        }
    }

    private String sanitizeUrl(String url) {
        try {
            URI uri = URI.create(url);
            String query = uri.getQuery();
            if(query == null) {
                query = "";
            }
            List<String> params = new ArrayList<>();
            for (String param : query.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && isSensitiveQueryParam(kv[0])) {
                    params.add(kv[0] + "=***redacted***");
                } else {
                    params.add(param);
                }
            }
            return new URI(uri.getScheme(), "example.com", uri.getPath(), String.join("&", params), uri.getFragment()).toString();
        } catch (Exception e) {
            logging.logToError("URL sanitize error: " + e.getMessage());
            return url;
        }
    }

    private String sanitizeBody(String body, String contentType) {
        if (contentType == null || body.isEmpty()) return body;

        if (contentType.startsWith("application/x-www-form-urlencoded")) {
            List<String> params = new ArrayList<>();
            for (String param : body.split("&")) {
                String[] kv = param.split("=", 2);
                if (kv.length == 2 && isSensitiveBodyParam(kv[0])) {
                    params.add(kv[0] + "=***redacted***");
                } else {
                    params.add(param);
                }
            }
            return String.join("&", params);
        } else if (contentType.startsWith("application/json")) {
            try {
                JSONObject json = new JSONObject(body);
                sanitizeJson(json);
                return json.toString();
            } catch (JSONException e) {
                return body;
            }
        }
        return body;
    }

    private void sanitizeJson(JSONObject json) {
        for (String key : json.keySet()) {
            if (isSensitiveBodyParam(key)) {
                json.put(key, "***redacted***");
            } else if (json.get(key) instanceof JSONObject) {
                sanitizeJson(json.getJSONObject(key));
            } else if (json.get(key) instanceof JSONArray) {
                JSONArray arr = json.getJSONArray(key);
                for (int i = 0; i < arr.length(); i++) {
                    if (arr.get(i) instanceof JSONObject) {
                        sanitizeJson(arr.getJSONObject(i));
                    }
                }
            }
        }
    }


    private boolean isSensitiveHeader(String headerName) {
        String lower = headerName.toLowerCase();
        return lower.contains("authorization") || lower.contains("cookie") || lower.contains("api-key") || lower.contains("token");
    }

    private boolean isSensitiveQueryParam(String paramName) {
        String lower = paramName.toLowerCase();
        return lower.contains("token") || lower.contains("key") || lower.contains("secret");
    }

    private boolean isSensitiveBodyParam(String paramName) {
        String lower = paramName.toLowerCase();
        return lower.contains("password") || lower.contains("secret") || lower.contains("credit_card") || lower.contains("key");
    }

    private JPanel createSuitePanel() {
        JPanel mainPanel = new JPanel(new BorderLayout());
        JPanel settingsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5,5,5,5);
        gbc.anchor = GridBagConstraints.LINE_START;

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 1;
        JLabel endpointLabel = new JLabel("API Endpoint:");
        settingsPanel.add(endpointLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.gridwidth = GridBagConstraints.REMAINDER; // 占据剩余列
        JTextField endpointField = new JTextField(30);
        endpointField.setText("https://api.siliconflow.cn/v1/chat/completions"); // 默认值
        settingsPanel.add(endpointField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 1;
        JLabel apiKeyLabel = new JLabel("API Key:");
        settingsPanel.add(apiKeyLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        JTextField apiKeyField = new JTextField(30);
        apiKeyField.setText(this.APIKEY);
        settingsPanel.add(apiKeyField, gbc);

        gbc.gridx = 0;
        gbc.gridy = 2;
        JLabel modelLabel = new JLabel("Model:");
        settingsPanel.add(modelLabel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 2;
        JTextField modelField = new JTextField(30);
        modelField.setText(this.model);
        settingsPanel.add(modelField, gbc);

        gbc.gridx = 1;
        gbc.gridy = 3;
        gbc.gridwidth = GridBagConstraints.REMAINDER;
        JButton saveButton = new JButton("Save Settings");
        saveButton.addActionListener(e -> {
            this.APIEndpoint = endpointField.getText().trim();
            this.APIKEY = apiKeyField.getText().trim();
            this.model = modelField.getText().trim();

            this.api.persistence().preferences().setString(PreferenceAPIEndpoint, this.APIEndpoint);
            this.api.persistence().preferences().setString(PreferenceAPIKEY, this.APIKEY);
            this.api.persistence().preferences().setString(PreferenceAPIModel, this.model);
        });
        settingsPanel.add(saveButton, gbc);

        mainPanel.add(settingsPanel, BorderLayout.CENTER);
        return mainPanel;
    }
}
