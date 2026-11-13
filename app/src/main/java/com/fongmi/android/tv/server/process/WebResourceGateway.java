package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.proxy.MihomoManager;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.tls.TlsClientManager;
import com.fongmi.android.tv.web.CookieBridge;
import com.fongmi.android.tv.web.HeaderPolicy;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

public class WebResourceGateway implements Process {

    private final Gson gson = new Gson();

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/webResource");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (session.getMethod() == NanoHTTPD.Method.OPTIONS) return cors(NanoHTTPD.newFixedLengthResponse(Response.Status.NO_CONTENT, NanoHTTPD.MIME_PLAINTEXT, ""), session);
        Map<String, String> params = session.getParms();
        String imp = params.get("imp");
        // imp 参数 = 浏览器指纹档：转手给 libtlsclient.so 的本地桥，由 tls-client 仿真发出。
        // 桥挂掉时返回的 JSON 带 error，这里转成 502 交给调用方回落。
        if (!TextUtils.isEmpty(imp)) return cors(tlsForward(session, params, imp, files), session);
        okhttp3.Response response = null;
        try {
            String target = params.get("url");
            if (!isAllowed(target)) return cors(Nano.error(Response.Status.BAD_REQUEST, "Unsupported url"), session);
            Map<String, String> headers = HeaderPolicy.withDefaultUa(HeaderPolicy.parse(params.get("headers")));
            copyRange(session, headers);
            Request.Builder builder = new Request.Builder().url(target).headers(HeaderPolicy.of(headers));
            CookieBridge.apply(builder.build().url(), builder, "include".equals(params.get("credentials")), HeaderPolicy.hasCookie(headers));
            builder.method(getMethod(session), getBody(session, builder.build().headers(), files));
            Request request = builder.build();
            long start = System.currentTimeMillis();
            SpiderDebug.log("web-resource", "%s %s", request.method(), request.url());
            response = OkHttp.client().newCall(request).execute();
            SpiderDebug.log("web-resource", "%s %s -> %s in %sms", request.method(), request.url(), response.code(), System.currentTimeMillis() - start);
            CookieBridge.set(target, response.headers());
            return cors(toResponse(response), session);
        } catch (Throwable e) {
            if (response != null) response.close();
            SpiderDebug.log("web-resource", e);
            return cors(Nano.error(e.getMessage()), session);
        }
    }

    // ---------------- 浏览器指纹档（libtlsclient.so 本地桥） ----------------

    /**
     * py/js 源：/webResource?url=..&imp=chrome_131[&headers=..&body=..&method=..]
     * 转成 /tlsclient JSON 协议转发。桥不可用/出错 → 502，调用方按既定回落链走。
     */
    private Response tlsForward(IHTTPSession session, Map<String, String> params, String imp, Map<String, String> files) {
        try {
            String target = params.get("url");
            if (!isAllowed(target)) return Nano.error(Response.Status.BAD_REQUEST, "Unsupported url");
            int port = TlsClientManager.ensure();
            if (port <= 0) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "tls bridge unavailable");
            JsonObject payload = new JsonObject();
            payload.addProperty("url", target);
            payload.addProperty("method", getMethod(session));
            payload.addProperty("impersonate", imp);
            String headersJson = params.get("headers");
            if (!TextUtils.isEmpty(headersJson)) {
                Map<String, String> headers = HeaderPolicy.parse(headersJson);
                JsonObject h = new JsonObject();
                for (Map.Entry<String, String> e : headers.entrySet()) h.addProperty(e.getKey(), e.getValue());
                payload.add("headers", h);
            }
            String body = params.get("body");
            if (TextUtils.isEmpty(body)) body = files.get("postData");
            if (!TextUtils.isEmpty(body)) payload.addProperty("bodyB64", Base64.encodeToString(body.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
            String follow = params.get("follow");
            payload.addProperty("followRedirects", "1".equals(follow) || "true".equalsIgnoreCase(follow));
            // 代理联动：源显式传 proxy 参数优先；否则 mihomo 在跑就自动走 18890 出站
            // （与 OkHttp 网关档继承 OkProxySelector 的行为对齐）。都不满足 = 直连。
            String proxy = params.get("proxy");
            if (TextUtils.isEmpty(proxy) && MihomoManager.get().isRunning()) {
                proxy = "http://127.0.0.1:" + MihomoManager.getMixedPort();
            }
            if (!TextUtils.isEmpty(proxy)) payload.addProperty("proxy", proxy);
            String timeout = params.get("timeout");
            try {
                if (!TextUtils.isEmpty(timeout)) payload.addProperty("timeout", Integer.parseInt(timeout));
            } catch (NumberFormatException ignored) {
            }
            Request request = new Request.Builder().url("http://127.0.0.1:" + port + "/tlsclient").post(RequestBody.create(gson.toJson(payload), MediaType.parse("application/json"))).build();
            okhttp3.Response response = OkHttp.client().newCall(request).execute();
            try {
                String json = response.body() == null ? "" : response.body().string();
                if (response.code() != 200) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "tls bridge http " + response.code());
                JsonObject resp = gson.fromJson(json, JsonObject.class);
                String error = resp.has("error") && !resp.get("error").isJsonNull() ? resp.get("error").getAsString() : "";
                if (!error.isEmpty()) return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "tls bridge: " + error);
                int status = resp.has("status") ? resp.get("status").getAsInt() : 200;
                Response result;
                if (resp.has("bodyB64") && !resp.get("bodyB64").isJsonNull()) {
                    byte[] bytes = Base64.decode(resp.get("bodyB64").getAsString(), Base64.DEFAULT);
                    result = NanoHTTPD.newFixedLengthResponse(Response.Status.lookup(status) == null ? Response.Status.OK : Response.Status.lookup(status), contentType(resp), new java.io.ByteArrayInputStream(bytes), bytes.length);
                } else {
                    String text = resp.has("body") && !resp.get("body").isJsonNull() ? resp.get("body").getAsString() : "";
                    result = NanoHTTPD.newFixedLengthResponse(Response.Status.lookup(status) == null ? Response.Status.OK : Response.Status.lookup(status), contentType(resp), text);
                }
                for (Map.Entry<String, com.google.gson.JsonElement> e : (resp.has("headers") ? resp.getAsJsonObject("headers") : new JsonObject()).entrySet()) {
                    if (copyHeader(e.getKey())) result.addHeader(e.getKey(), e.getValue().getAsString());
                }
                return result;
            } finally {
                response.close();
            }
        } catch (Throwable e) {
            SpiderDebug.log("tls-forward", e);
            return Nano.error(Response.Status.SERVICE_UNAVAILABLE, "tls bridge: " + e.getMessage());
        }
    }

    private String contentType(JsonObject resp) {
        if (resp.has("headers") && !resp.get("headers").isJsonNull()) {
            for (Map.Entry<String, com.google.gson.JsonElement> e : resp.getAsJsonObject("headers").entrySet()) {
                if ("content-type".equalsIgnoreCase(e.getKey())) return e.getValue().getAsString();
            }
        }
        return NanoHTTPD.MIME_PLAINTEXT;
    }

    private boolean isAllowed(String url) {
        return !TextUtils.isEmpty(url) && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private void copyRange(IHTTPSession session, Map<String, String> headers) {
        String range = session.getHeaders().get("range");
        if (!TextUtils.isEmpty(range)) headers.put("Range", range);
    }

    private String getMethod(IHTTPSession session) {
        String method = session.getMethod().name();
        return "OPTIONS".equals(method) ? "GET" : method;
    }

    private RequestBody getBody(IHTTPSession session, Headers headers, Map<String, String> files) {
        String method = getMethod(session);
        if ("GET".equals(method) || "HEAD".equals(method)) return null;
        String body = session.getParms().get("body");
        if (TextUtils.isEmpty(body)) body = files.get("postData");
        String contentType = headers.get("Content-Type");
        MediaType type = MediaType.parse(contentType == null ? "text/plain; charset=utf-8" : contentType);
        return RequestBody.create(body == null ? "" : body, type);
    }

    private Response toResponse(okhttp3.Response response) {
        InputStream stream = response.body().byteStream();
        String type = getContentType(response);
        Response.Status status = Response.Status.lookup(response.code());
        Response result = NanoHTTPD.newChunkedResponse(status == null ? Response.Status.OK : status, type, stream);
        for (String name : response.headers().names()) if (copyHeader(name)) for (String value : response.headers(name)) result.addHeader(name, value);
        return result;
    }

    private String getContentType(okhttp3.Response response) {
        MediaType type = response.body().contentType();
        return type == null ? NanoHTTPD.MIME_PLAINTEXT : type.toString();
    }

    private boolean copyHeader(String name) {
        return !"Connection".equalsIgnoreCase(name) && !"Transfer-Encoding".equalsIgnoreCase(name) && !"Keep-Alive".equalsIgnoreCase(name) && !"Content-Length".equalsIgnoreCase(name);
    }

    private Response cors(Response response, IHTTPSession session) {
        String origin = session.getHeaders().get("origin");
        response.addHeader("Access-Control-Allow-Origin", TextUtils.isEmpty(origin) ? "*" : origin);
        response.addHeader("Access-Control-Allow-Credentials", "true");
        response.addHeader("Access-Control-Allow-Methods", "GET,POST,PUT,PATCH,DELETE,HEAD,OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "*");
        response.addHeader("Access-Control-Expose-Headers", "*");
        response.addHeader("Access-Control-Max-Age", "86400");
        return response;
    }
}
