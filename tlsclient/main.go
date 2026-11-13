// 星秀 TLS 指纹桥 —— 独立 Go 小内核。
//
// 打包/exec 模式与 libmihomo.so 完全一致：编译成 arm64 二进制放进
// app/src/main/jniLibs/arm64-v8a/libtlsclient.so（"lib*.so" 命名让安装器
// 把它当 native lib 解压出来，useLegacyPackaging 保证是真实文件），
// Java 侧 TlsClientManager 用 ProcessBuilder 裸 exec 拉起。
//
// 职责单一：监听 127.0.0.1:<port>，把收到的 HTTP 请求用
// bogdanfinn/tls-client 以完整浏览器 TLS 指纹（JA3 + HTTP2 帧 + header 顺序）
// 重发到目标站，回传结果。不碰任何业务、不落盘任何敏感数据。
//
// 协议：
//   GET  /ping        -> {"ok":true,"version":"1"}
//   POST /tlsclient   -> body: TlsReq JSON，响应见 TlsResp JSON
//
// 无参数时端口默认 17890；由 Java 侧负责挑可用端口并传进来。
package main

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"strings"
	"sync"
	"time"

	tls_client "github.com/bogdanfinn/tls-client"
	fhttp "github.com/bogdanfinn/fhttp"
	"github.com/bogdanfinn/tls-client/profiles"
)

const version = "1"

// ---------- 协议结构 ----------

type TlsReq struct {
	URL             string            `json:"url"`
	Method          string            `json:"method,omitempty"`
	Headers         map[string]string `json:"headers,omitempty"`
	Body            string            `json:"body,omitempty"`    // 文本请求体
	BodyB64         string            `json:"bodyB64,omitempty"` // 二进制请求体（base64）
	Impersonate     string            `json:"impersonate,omitempty"`
	Proxy           string            `json:"proxy,omitempty"`
	Timeout         int               `json:"timeout,omitempty"`   // 秒，默认 30
	FollowRedirects bool              `json:"followRedirects,omitempty"`
	HeaderOrder     []string          `json:"headerOrder,omitempty"` // 可选：显式 header 顺序
}

type TlsResp struct {
	Status   int               `json:"status"`
	Headers  map[string]string `json:"headers"`
	IsText   bool              `json:"isText"`
	Body     string            `json:"body,omitempty"`     // IsText 时填充
	BodyB64  string            `json:"bodyB64,omitempty"`  // !IsText 时填充
	FinalURL string            `json:"finalUrl,omitempty"`
	Error    string            `json:"error,omitempty"`
}

// ---------- 客户端缓存（同 key 复用连接与 cookie jar）----------

type clientKey struct {
	profile string
	proxy   string
}

var (
	mu      sync.Mutex
	clients = make(map[clientKey]tls_client.HttpClient)
)

func resolveProfile(name string) profiles.ClientProfile {
	// 常用名归一化；找不到就回退 chrome_131（广泛验证过的稳定档）。
	if name == "" {
		name = "chrome_131"
	}
	name = strings.ToLower(strings.TrimSpace(name))
	if p, ok := profiles.MappedTLSClients[name]; ok {
		return p
	}
	if p, ok := profiles.MappedTLSClients["chrome_131"]; ok {
		return p
	}
	return profiles.DefaultClientProfile
}

func getClient(key clientKey, timeout int, follow bool) (tls_client.HttpClient, error) {
	mu.Lock()
	defer mu.Unlock()
	if c, ok := clients[key]; ok {
		return c, nil
	}
	jar := tls_client.NewCookieJar()
	opts := []tls_client.HttpClientOption{
		tls_client.WithTimeoutSeconds(timeout),
		tls_client.WithClientProfile(resolveProfile(key.profile)),
		tls_client.WithCookieJar(jar),
	}
	if !follow {
		opts = append(opts, tls_client.WithNotFollowRedirects())
	}
	if key.proxy != "" {
		opts = append(opts, tls_client.WithProxyUrl(key.proxy))
	}
	c, err := tls_client.NewHttpClient(tls_client.NewNoopLogger(), opts...)
	if err != nil {
		return nil, err
	}
	clients[key] = c
	return c, nil
}

// ---------- 请求处理 ----------

func isTextContentType(h map[string]string) bool {
	ct := ""
	for k, v := range h {
		if strings.EqualFold(k, "content-type") {
			ct = strings.ToLower(v)
			break
		}
	}
	if ct == "" {
		return false
	}
	return strings.HasPrefix(ct, "text/") ||
		strings.Contains(ct, "json") ||
		strings.Contains(ct, "xml") ||
		strings.Contains(ct, "javascript")
}

// ---------- 默认 UA（指纹档对应的浏览器 UA；源没传 UA 时补上）----------

func defaultUA(profile string) string {
	ver := ""
	if i := strings.IndexByte(profile, '_'); i >= 0 {
		ver = profile[i+1:]
	}
	switch {
	case strings.HasPrefix(profile, "chrome_"):
		return fmt.Sprintf("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/%s.0.0.0 Safari/537.36", ver)
	case strings.HasPrefix(profile, "firefox_"):
		return fmt.Sprintf("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:%s.0) Gecko/20100101 Firefox/%s.0", ver, ver)
	case strings.HasPrefix(profile, "safari_"):
		return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Safari/605.1.15"
	default:
		return ""
	}
}

func handleTls(w http.ResponseWriter, r *http.Request) {
	var req TlsReq
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeJSON(w, http.StatusBadRequest, TlsResp{Error: "bad json: " + err.Error()})
		return
	}
	if req.URL == "" {
		writeJSON(w, http.StatusBadRequest, TlsResp{Error: "url required"})
		return
	}
	timeout := req.Timeout
	if timeout <= 0 {
		timeout = 30
	}
	if timeout > 120 {
		timeout = 120
	}
	method := req.Method
	if method == "" {
		method = http.MethodGet
	}

	client, err := getClient(clientKey{profile: req.Impersonate, proxy: req.Proxy}, timeout, req.FollowRedirects)
	if err != nil {
		writeJSON(w, http.StatusOK, TlsResp{Error: "client: " + err.Error()})
		return
	}

	var bodyReader io.Reader
	if req.BodyB64 != "" {
		if b, e := base64.StdEncoding.DecodeString(req.BodyB64); e == nil {
			bodyReader = bytes.NewReader(b)
		} else {
			writeJSON(w, http.StatusBadRequest, TlsResp{Error: "bad bodyB64"})
			return
		}
	} else if req.Body != "" {
		bodyReader = strings.NewReader(req.Body)
	}

	httpReq, err := fhttp.NewRequest(method, req.URL, bodyReader)
	if err != nil {
		writeJSON(w, http.StatusOK, TlsResp{Error: "new request: " + err.Error()})
		return
	}
	hasUA := false
	for k, v := range req.Headers {
		if strings.EqualFold(k, "host") {
			continue // 交给 net/http 自己算
		}
		if strings.EqualFold(k, "user-agent") && v != "" {
			hasUA = true
		}
		httpReq.Header.Set(k, v)
	}
	if !hasUA {
		// 指纹档没配 UA 会很显眼（TLS 是 Chrome、UA 是 Go），补齐浏览器 UA。
		ua := defaultUA(strings.ToLower(strings.TrimSpace(req.Impersonate)))
		if ua != "" {
			httpReq.Header.Set("User-Agent", ua)
		}
	}
	if len(req.HeaderOrder) > 0 {
		// tls-client 的 fhttp 用 "header-order" 伪 key 控制真实发送顺序。
		httpReq.Header["header-order"] = []string{strings.Join(req.HeaderOrder, ",")}
	}

	resp, err := client.Do(httpReq)
	if err != nil {
		writeJSON(w, http.StatusOK, TlsResp{Error: "do: " + err.Error()})
		return
	}
	defer resp.Body.Close()

	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		writeJSON(w, http.StatusOK, TlsResp{Status: resp.StatusCode, Error: "read: " + err.Error()})
		return
	}

	headers := make(map[string]string)
	for k, vs := range resp.Header {
		if len(vs) > 0 {
			headers[k] = vs[0]
		}
	}
	out := TlsResp{Status: resp.StatusCode, Headers: headers, IsText: isTextContentType(headers)}
	// BodyB64 无损恒填（Java/py 转发用）；Body 仅供文本场景直接取用。
	out.BodyB64 = base64.StdEncoding.EncodeToString(raw)
	if out.IsText {
		out.Body = string(raw)
	}
	if resp.Request != nil && resp.Request.URL != nil {
		out.FinalURL = resp.Request.URL.String()
	}
	writeJSON(w, http.StatusOK, out)
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

// ---------- 入口 ----------

func main() {
	port := flag.String("port", "17890", "listen port on 127.0.0.1")
	flag.Parse()

	mux := http.NewServeMux()
	mux.HandleFunc("/ping", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "version": version})
	})
	mux.HandleFunc("/tlsclient", handleTls)

	ln, err := net.Listen("tcp", "127.0.0.1:"+*port)
	if err != nil {
		// 端口被占：静默退出（Java 侧 health check 会发现没起来并换端口重试）。
		fmt.Fprintln(os.Stderr, "listen:", err)
		os.Exit(1)
	}
	srv := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
	}
	log.SetFlags(0)
	log.Println("tlsclient ready on 127.0.0.1:" + *port)
	if err := srv.Serve(ln); err != nil {
		fmt.Fprintln(os.Stderr, "serve:", err)
		os.Exit(1)
	}
}
