package com.fourtwo.fakecontactbook.ui;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.fakecontactbook.R;
import com.fourtwo.fakecontactbook.http.HttpApiServerManager;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HttpApiActivity extends AppCompatActivity {
    private static final String PREFS = "http_api_config";
    private static final String KEY_PORT = "port";

    private TextInputEditText inputPort;
    private TextInputEditText inputRequestPath;
    private TextInputEditText inputRequestBody;
    private Spinner spinnerEndpoint;
    private TextView textStatus;
    private TextView textDoc;
    private TextView textResponse;

    private MaterialButton btnSavePort;
    private MaterialButton btnStart;
    private MaterialButton btnStop;
    private MaterialButton btnTest;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ArrayList<ApiExample> examples = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_http_api);

        bindViews();
        setupToolbar();
        setupExamples();
        setupButtons();
        loadPort();

        refreshStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
    }

    private void bindViews() {
        inputPort = findViewById(R.id.inputHttpPort);
        inputRequestPath = findViewById(R.id.inputHttpRequestPath);
        inputRequestBody = findViewById(R.id.inputHttpRequestBody);
        spinnerEndpoint = findViewById(R.id.spinnerHttpEndpoint);
        textStatus = findViewById(R.id.textHttpStatus);
        textDoc = findViewById(R.id.textHttpDoc);
        textResponse = findViewById(R.id.textHttpResponse);

        btnSavePort = findViewById(R.id.btnSaveHttpPort);
        btnStart = findViewById(R.id.btnStartHttpApi);
        btnStop = findViewById(R.id.btnStopHttpApi);
        btnTest = findViewById(R.id.btnTestHttpApi);
    }

    private void setupToolbar() {
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
    }

    private void setupExamples() {
        examples.clear();

        examples.add(new ApiExample(
                "GET /",
                "GET",
                "/",
                "",
                "用途：获取 HTTP API 的接口文档。\n\n" +
                        "请求参数：无。\n\n" +
                        "响应字段：\n" +
                        "- ok：是否成功。\n" +
                        "- name：服务名称。\n" +
                        "- baseUrl：当前服务地址。\n" +
                        "- defaultPort：默认端口，当前为 9420。\n" +
                        "- endpoints：接口列表。\n\n" +
                        "测试建议：用于确认服务能返回文档。"
        ));

        examples.add(new ApiExample(
                "GET /health",
                "GET",
                "/health",
                "",
                "用途：检测 HTTP API 服务是否正在运行。\n\n" +
                        "请求参数：无。\n\n" +
                        "响应字段：\n" +
                        "- ok：是否成功。\n" +
                        "- server：服务名称。\n" +
                        "- port：当前端口。\n" +
                        "- baseUrl：当前基础地址。\n" +
                        "- time：服务端当前时间戳。\n\n" +
                        "测试建议：启动服务后优先测试这个接口。"
        ));

        examples.add(new ApiExample(
                "GET /stats",
                "GET",
                "/stats",
                "",
                "用途：获取宿主端统计信息。\n\n" +
                        "请求参数：无。\n\n" +
                        "响应字段：\n" +
                        "- profileCount：通讯录方案数量。\n" +
                        "- contactCount：全部方案联系人总数。\n" +
                        "- enabledRuleCount：已开启的 App 规则数量。\n" +
                        "- socialProfileRecordCount：第三方社交资料记录数量。\n" +
                        "- global.enabled：全局 Hook 是否开启。\n" +
                        "- global.profileId：全局 Hook 当前绑定的方案 ID。"
        ));

        examples.add(new ApiExample(
                "GET /profiles",
                "GET",
                "/profiles",
                "",
                "用途：获取所有通讯录方案。\n\n" +
                        "请求参数：无。\n\n" +
                        "响应字段：\n" +
                        "- profiles：方案数组。\n" +
                        "  - id：方案 ID。\n" +
                        "  - name：方案名称。\n" +
                        "  - contacts：联系人数组。\n" +
                        "- count：方案数量。\n\n" +
                        "测试建议：后续需要 profileId 的接口，先调用这个接口复制 id。"
        ));

        examples.add(new ApiExample(
                "POST /profiles",
                "POST",
                "/profiles",
                "{\n  \"name\": \"HTTP测试方案\"\n}",
                "用途：新建一个通讯录方案。\n\n" +
                        "请求 Body：\n" +
                        "- name：方案名称，可选；为空时使用默认名称。\n\n" +
                        "响应字段：\n" +
                        "- profile：新建后的方案对象。\n\n" +
                        "示例 Body：\n" +
                        "{\n" +
                        "  \"name\": \"HTTP测试方案\"\n" +
                        "}"
        ));

        examples.add(new ApiExample(
                "GET /profiles/{profileId}",
                "GET",
                "/profiles/{profileId}",
                "",
                "用途：获取指定通讯录方案详情。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID，需要从 GET /profiles 获取。\n\n" +
                        "响应字段：\n" +
                        "- profile：方案对象。\n\n" +
                        "测试方法：先调用 GET /profiles，复制某个 id，把路径里的 {profileId} 替换掉。"
        ));

        examples.add(new ApiExample(
                "PUT /profiles/{profileId}",
                "PUT",
                "/profiles/{profileId}",
                "{\n  \"name\": \"修改后的方案名\"\n}",
                "用途：修改指定方案名称。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID。\n\n" +
                        "请求 Body：\n" +
                        "- name：新的方案名称。\n\n" +
                        "响应字段：\n" +
                        "- profile：修改后的方案对象。"
        ));

        examples.add(new ApiExample(
                "DELETE /profiles/{profileId}",
                "DELETE",
                "/profiles/{profileId}",
                "",
                "用途：删除指定通讯录方案。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID。\n\n" +
                        "响应字段：\n" +
                        "- deletedProfileId：已删除的方案 ID。\n\n" +
                        "注意：删除方案后，绑定这个方案的规则会被清理或失效。"
        ));

        examples.add(new ApiExample(
                "GET /profiles/{profileId}/contacts",
                "GET",
                "/profiles/{profileId}/contacts",
                "",
                "用途：获取某个方案下的联系人列表。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID。\n\n" +
                        "响应字段：\n" +
                        "- profileId：方案 ID。\n" +
                        "- contacts：联系人数组。\n" +
                        "  - id：联系人 ID。\n" +
                        "  - name：姓名。\n" +
                        "  - phone：手机号。\n" +
                        "  - email：邮箱。\n" +
                        "  - socialProfiles：第三方社交资料数组。\n" +
                        "- count：联系人数量。"
        ));

        examples.add(new ApiExample(
                "POST /profiles/{profileId}/contacts",
                "POST",
                "/profiles/{profileId}/contacts",
                "{\n  \"contacts\": [\n    {\n      \"name\": \"张三\",\n      \"phone\": \"13800138000\",\n      \"email\": \"zhangsan@example.com\"\n    },\n    {\n      \"name\": \"李四\",\n      \"phone\": \"13900139000\"\n    }\n  ]\n}",
                "用途：向指定方案新增联系人，支持单个或批量。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID。\n\n" +
                        "请求 Body 两种写法：\n\n" +
                        "单个联系人：\n" +
                        "{\n" +
                        "  \"name\": \"张三\",\n" +
                        "  \"phone\": \"13800138000\",\n" +
                        "  \"email\": \"zhangsan@example.com\"\n" +
                        "}\n\n" +
                        "批量联系人：\n" +
                        "{\n" +
                        "  \"contacts\": [\n" +
                        "    {\"name\":\"张三\",\"phone\":\"13800138000\"},\n" +
                        "    {\"name\":\"李四\",\"phone\":\"13900139000\"}\n" +
                        "  ]\n" +
                        "}\n\n" +
                        "字段说明：\n" +
                        "- name：姓名，可空。\n" +
                        "- phone：手机号，必填；为空不会添加。\n" +
                        "- email：邮箱，可空。\n\n" +
                        "响应字段：\n" +
                        "- added：成功添加数量。\n" +
                        "- profile：添加后的完整方案对象。"
        ));

        examples.add(new ApiExample(
                "PUT /profiles/{profileId}/contacts/{contactId}",
                "PUT",
                "/profiles/{profileId}/contacts/{contactId}",
                "{\n  \"name\": \"王五\",\n  \"phone\": \"13700137000\",\n  \"email\": \"wangwu@example.com\"\n}",
                "用途：修改指定联系人。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID。\n" +
                        "- contactId：联系人 ID，可从 GET /profiles/{profileId}/contacts 获取。\n\n" +
                        "请求 Body：\n" +
                        "- name：姓名，可选。\n" +
                        "- phone：手机号，可选。\n" +
                        "- email：邮箱，可选。\n\n" +
                        "响应字段：\n" +
                        "- contact：修改后的联系人对象。"
        ));

        examples.add(new ApiExample(
                "DELETE /profiles/{profileId}/contacts/{contactId}",
                "DELETE",
                "/profiles/{profileId}/contacts/{contactId}",
                "",
                "用途：删除指定联系人。\n\n" +
                        "路径参数：\n" +
                        "- profileId：方案 ID。\n" +
                        "- contactId：联系人 ID。\n\n" +
                        "响应字段：\n" +
                        "- profileId：方案 ID。\n" +
                        "- contactId：联系人 ID。\n" +
                        "- removed：是否删除成功。"
        ));

        examples.add(new ApiExample(
                "GET /rules",
                "GET",
                "/rules",
                "",
                "用途：获取所有 App 规则。\n\n" +
                        "请求参数：无。\n\n" +
                        "响应字段：\n" +
                        "- rules：规则数组。\n" +
                        "  - packageName：目标 App 包名。\n" +
                        "  - enabled：规则是否开启。\n" +
                        "  - profileId：绑定的通讯录方案 ID。\n" +
                        "- count：规则数量。"
        ));

        examples.add(new ApiExample(
                "GET /rules/{packageName}",
                "GET",
                "/rules/com.example.app",
                "",
                "用途：获取指定 App 的规则。\n\n" +
                        "路径参数：\n" +
                        "- packageName：目标 App 包名，例如 com.eg.android.AlipayGphone。\n\n" +
                        "响应字段：\n" +
                        "- rule.packageName：目标包名。\n" +
                        "- rule.enabled：是否开启。\n" +
                        "- rule.profileId：绑定方案 ID。"
        ));

        examples.add(new ApiExample(
                "PUT /rules/{packageName}",
                "PUT",
                "/rules/com.example.app",
                "{\n  \"enabled\": true,\n  \"profileId\": \"请替换为真实profileId\"\n}",
                "用途：设置指定 App 规则。\n\n" +
                        "路径参数：\n" +
                        "- packageName：目标 App 包名。\n\n" +
                        "请求 Body：\n" +
                        "- enabled：是否开启规则。\n" +
                        "- profileId：绑定的通讯录方案 ID。\n\n" +
                        "响应字段：\n" +
                        "- rule：保存后的规则对象。\n\n" +
                        "示例：\n" +
                        "PUT /rules/com.eg.android.AlipayGphone\n" +
                        "{\n" +
                        "  \"enabled\": true,\n" +
                        "  \"profileId\": \"xxx\"\n" +
                        "}"
        ));

        examples.add(new ApiExample(
                "DELETE /rules/{packageName}",
                "DELETE",
                "/rules/com.example.app",
                "",
                "用途：删除指定 App 规则。\n\n" +
                        "路径参数：\n" +
                        "- packageName：目标 App 包名。\n\n" +
                        "响应字段：\n" +
                        "- deletedPackageName：删除的包名。"
        ));

        examples.add(new ApiExample(
                "GET /global",
                "GET",
                "/global",
                "",
                "用途：获取全局 Hook 配置。\n\n" +
                        "请求参数：无。\n\n" +
                        "响应字段：\n" +
                        "- enabled：全局 Hook 是否开启。\n" +
                        "- profileId：全局绑定的方案 ID。\n" +
                        "- profile：当前全局方案对象，可能为空。"
        ));

        examples.add(new ApiExample(
                "PUT /global",
                "PUT",
                "/global",
                "{\n  \"enabled\": true,\n  \"profileId\": \"请替换为真实profileId\"\n}",
                "用途：设置全局 Hook 配置。\n\n" +
                        "请求 Body：\n" +
                        "- enabled：是否开启全局 Hook。\n" +
                        "- profileId：全局绑定方案 ID。\n\n" +
                        "响应字段：\n" +
                        "- enabled：保存后的全局开关。\n" +
                        "- profileId：保存后的方案 ID。\n" +
                        "- profile：当前全局方案对象。"
        ));

        examples.add(new ApiExample(
                "POST /social/upsert",
                "POST",
                "/social/upsert",
                "{\n  \"packageName\": \"com.tencent.mobileqq\",\n  \"phone\": \"13800138000\",\n  \"nickname\": \"测试昵称\",\n  \"account\": \"10001\",\n  \"avatarBase64\": \"\"\n}",
                "用途：提交第三方社交资料，按 packageName + phone 归档到联系人。\n\n" +
                        "请求 Body 必填字段：\n" +
                        "- packageName：社交平台 App 包名。\n" +
                        "- phone：手机号，用来匹配联系人。\n\n" +
                        "请求 Body 可选字段：\n" +
                        "- nickname / nickName / name：昵称。\n" +
                        "- account / userId / uid / openId：账号。\n" +
                        "- avatarBase64：头像 Base64。\n" +
                        "- avatarUrl：头像 URL。\n" +
                        "- createIfMissing：手机号不存在时是否创建联系人。\n" +
                        "- profileId：指定写入某个方案。\n\n" +
                        "响应字段：\n" +
                        "- ok：是否成功。\n" +
                        "- reason：失败原因或 ok。\n" +
                        "- packageName：平台包名。\n" +
                        "- phone：原始手机号。\n" +
                        "- normalizedPhone：归一化手机号。\n" +
                        "- matchedCount：匹配到的联系人数量。"
        ));

        examples.add(new ApiExample(
                "GET /generator/phones",
                "GET",
                "/generator/phones?incompletePhone=1380013****&maxResults=20&isp=不限",
                "",
                "用途：生成手机号。\n\n" +
                        "Query 参数：\n" +
                        "- incompletePhone：手机号模板，11 位，支持 *，例如 1380013****。\n" +
                        "- cityName：城市名，可选。\n" +
                        "- isp：运营商，可选：不限、移动、联通、电信。\n" +
                        "- maxResults：最大生成数量，0 表示不限制。\n" +
                        "- useDb：是否使用本地号段库，true/false。\n\n" +
                        "响应字段：\n" +
                        "- count：生成数量。\n" +
                        "- phones：手机号数组。\n" +
                        "- maxResults：本次上限。\n" +
                        "- useDb：是否使用数据库。"
        ));

        examples.add(new ApiExample(
                "POST /generator/phones",
                "POST",
                "/generator/phones",
                "{\n  \"incompletePhone\": \"1380013****\",\n  \"cityName\": \"\",\n  \"isp\": \"不限\",\n  \"maxResults\": 20,\n  \"useDb\": false\n}",
                "用途：生成手机号，POST JSON 版本。\n\n" +
                        "请求 Body：\n" +
                        "- incompletePhone：手机号模板，必填。\n" +
                        "- cityName：城市名，可空。\n" +
                        "- isp：运营商，可选：不限、移动、联通、电信。\n" +
                        "- maxResults：最大生成数量。\n" +
                        "- useDb：是否使用本地号段库。\n\n" +
                        "响应字段：\n" +
                        "- count：生成数量。\n" +
                        "- phones：手机号数组。\n" +
                        "- maxResults：本次上限。\n" +
                        "- useDb：是否使用数据库。"
        ));

        ArrayList<String> labels = new ArrayList<>();

        for (ApiExample example : examples) {
            labels.add(example.label);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                this,
                android.R.layout.simple_spinner_item,
                labels
        );
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerEndpoint.setAdapter(adapter);

        spinnerEndpoint.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                updateSelectedExample();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        updateSelectedExample();
    }

    private void setupButtons() {
        btnSavePort.setOnClickListener(v -> savePort());

        btnStart.setOnClickListener(v -> {
            int port = parsePort();

            if (port <= 0) {
                return;
            }

            savePortValue(port);

            try {
                HttpApiServerManager.start(this, port);
                Toast.makeText(this, "HTTP API 已启动", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
            }

            refreshStatus();
        });

        btnStop.setOnClickListener(v -> {
            HttpApiServerManager.stop();
            Toast.makeText(this, "HTTP API 已停止", Toast.LENGTH_SHORT).show();
            refreshStatus();
        });

        btnTest.setOnClickListener(v -> testSelectedEndpoint());
    }

    private void loadPort() {
        int port = prefs().getInt(KEY_PORT, HttpApiServerManager.DEFAULT_PORT);
        inputPort.setText(String.valueOf(port));
    }

    private void savePort() {
        int port = parsePort();

        if (port <= 0) {
            return;
        }

        savePortValue(port);
        Toast.makeText(this, "端口已保存：" + port, Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void savePortValue(int port) {
        prefs().edit().putInt(KEY_PORT, port).apply();
    }

    private int parsePort() {
        String value = inputPort.getText() == null ? "" : inputPort.getText().toString().trim();

        try {
            int port = Integer.parseInt(value);

            if (port <= 0 || port > 65535) {
                inputPort.setError("端口必须在 1-65535 之间");
                return -1;
            }

            return port;
        } catch (Exception e) {
            inputPort.setError("请输入正确端口");
            return -1;
        }
    }

    private void refreshStatus() {
        boolean running = HttpApiServerManager.isRunning();
        int port = parsePortSilently();

        String baseUrl = "http://127.0.0.1:" + port;

        textStatus.setText(
                running
                        ? "状态：运行中\n地址：" + HttpApiServerManager.getBaseUrl()
                        : "状态：未运行\n默认地址：" + baseUrl
        );
    }

    private int parsePortSilently() {
        try {
            String value = inputPort.getText() == null ? "" : inputPort.getText().toString().trim();
            int port = Integer.parseInt(value);

            if (port <= 0 || port > 65535) {
                return HttpApiServerManager.DEFAULT_PORT;
            }

            return port;
        } catch (Exception ignored) {
            return HttpApiServerManager.DEFAULT_PORT;
        }
    }

    private void updateSelectedExample() {
        int index = spinnerEndpoint.getSelectedItemPosition();

        if (index < 0 || index >= examples.size()) {
            return;
        }

        ApiExample example = examples.get(index);

        inputRequestPath.setText(example.path);
        inputRequestBody.setText(example.body);

        textDoc.setText(
                "接口：" + example.method + " " + example.path + "\n\n"
                        + "Base URL：http://127.0.0.1:" + parsePortSilently() + "\n\n"
                        + example.doc
        );
    }

    private void testSelectedEndpoint() {
        int index = spinnerEndpoint.getSelectedItemPosition();

        if (index < 0 || index >= examples.size()) {
            return;
        }

        if (!HttpApiServerManager.isRunning()) {
            Toast.makeText(this, "请先启动 HTTP API", Toast.LENGTH_SHORT).show();
            return;
        }

        ApiExample example = examples.get(index);

        String path = inputRequestPath.getText() == null
                ? example.path
                : inputRequestPath.getText().toString().trim();

        if (TextUtils.isEmpty(path)) {
            inputRequestPath.setError("请求路径不能为空");
            return;
        }

        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        String body = inputRequestBody.getText() == null ? "" : inputRequestBody.getText().toString();

        textResponse.setText("请求中...");
        final String finalPath = path;
        executor.execute(() -> {
            String result;

            try {
                result = request(example.method, HttpApiServerManager.getBaseUrl() + finalPath, body);
            } catch (Exception e) {
                result = "请求失败：\n" + e;
            }

            String finalResult = result;

            runOnUiThread(() -> textResponse.setText(finalResult));
        });
    }

    private String request(String method, String urlText, String body) throws Exception {
        HttpURLConnection connection = null;

        try {
            URL url = new URL(urlText);
            connection = (HttpURLConnection) url.openConnection();

            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");

            boolean hasBody = !TextUtils.isEmpty(body)
                    && !"GET".equalsIgnoreCase(method)
                    && !"DELETE".equalsIgnoreCase(method);

            if (hasBody) {
                connection.setDoOutput(true);

                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.getBytes(StandardCharsets.UTF_8));
                }
            }

            int code = connection.getResponseCode();
            InputStream input = code >= 200 && code < 300
                    ? connection.getInputStream()
                    : connection.getErrorStream();

            String response = readStream(input);

            return "HTTP " + code + "\n\n" + response;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String readStream(InputStream input) throws Exception {
        if (input == null) {
            return "";
        }

        StringBuilder builder = new StringBuilder();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;

            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }

        return builder.toString();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private static class ApiExample {
        final String label;
        final String method;
        final String path;
        final String body;
        final String doc;

        ApiExample(String label, String method, String path, String body, String doc) {
            this.label = label;
            this.method = method;
            this.path = path;
            this.body = body == null ? "" : body;
            this.doc = doc == null ? "" : doc;
        }
    }
}