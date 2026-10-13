import re
import os
import json
import time
import requests
from lxml import etree
from com.github.catvod import Proxy
from com.chaquo.python import Python
from abc import abstractmethod, ABCMeta
from importlib.machinery import SourceFileLoader
from urllib.parse import quote, urlencode

# ============================================================================
# 网关通道（备用，默认关闭）
# ----------------------------------------------------------------------------
# 背景：内置 Chaquopy 的 requests 走 urllib3 -> socket.connect()，全程 HTTP/1.1，
# 且不经过壳子的 OkProxySelector —— 既用不上 mihomo，也过不了按 JA4 判定的风控
# （实测 Cloudflare 返回 403 "Just a moment"）。
#
# 做法：把请求交给壳子本地的 /webResource 端点，由 OkHttp 代发 —— 与 JAR 爬虫
# 同一套栈（HTTP/2 + Conscrypt），并继承 OkProxySelector 的代理规则。
#
#   Python --HTTP/1.1(仅本机)--> 壳子 /webResource --OkHttp/2--> 代理 --> 站点
#
# 开关优先级：站点 ext 的 gw 字段 > 全局 Prefers["py_gateway"] > 关闭
#   站点级：ext 填 {"gw":"1"}            （只让这一个源走网关）
#   全局：  Prefers.put("py_gateway","1") （所有 py 源都走，影响面大）
#
# 已知差异（相对真实 requests）：
#   - allow_redirects=False 失效：OkHttp 默认跟随重定向，Python 侧看不到 302
#   - stream=True 慎用：网关剔除了 Content-Length，响应是 chunked
#   - proxies= 被忽略：代理由 OkProxySelector 决定
#   - multipart/文件上传不支持：body 只能走 body 参数
#   - timeout 只约束本机那一跳，真实超时在壳子侧
# 任何环节异常都静默回落到原始 requests，保证不改变既有行为。
# ============================================================================

GATEWAY_PREF_KEY = "py_gateway"
GATEWAY_TIMEOUT = 25


def _gateway_port():
    try:
        return int(Proxy.getPort())
    except Exception:
        return 0


def _gateway_wanted(extend):
    """站点 ext 显式指定优先；没说则读全局开关。"""
    flag = None
    if extend:
        try:
            obj = json.loads(extend) if isinstance(extend, str) else extend
            if isinstance(obj, dict) and "gw" in obj:
                flag = str(obj["gw"]).strip().lower()
        except Exception:
            pass
    if flag is None:
        try:
            from com.github.catvod.utils import Prefers
            flag = str(Prefers.getString(GATEWAY_PREF_KEY, "0")).strip().lower()
        except Exception:
            flag = "0"
    return flag in ("1", "true", "on", "yes")


def _gateway_cookies(headers, cookies):
    out = {}
    for key, value in (headers or {}).items():
        if value is not None:
            out[str(key)] = str(value)
    if cookies and not any(k.lower() == "cookie" for k in out):
        if hasattr(cookies, "items"):
            pairs = list(cookies.items())
        elif isinstance(cookies, (list, tuple)):
            pairs = list(cookies)
        else:
            pairs = []
        jar = "; ".join("%s=%s" % (k, v) for k, v in pairs)
        if jar:
            out["Cookie"] = jar
    return out


def _gateway_response(text, status, content_type, url):
    """把网关回包包装成 requests.Response，复用它的 .text/.json()/.ok 等方法。"""
    from requests.structures import CaseInsensitiveDict
    raw = text.encode("utf-8") if isinstance(text, str) else (text or b"")
    rsp = requests.Response()
    rsp.status_code = int(status or 0)
    rsp._content = raw
    rsp.encoding = "utf-8"
    rsp.url = url
    rsp.headers = CaseInsensitiveDict({"Content-Type": content_type or "text/html"})
    return rsp


def gateway_request(method, url, headers=None, cookies=None, params=None,
                    data=None, json_body=None, timeout=None, **_ignored):
    """经壳子网关发一个请求。

    :return: requests.Response；端口不可用或调用方没开网关时返回 None，
             由调用方决定是否回落。
    """
    port = _gateway_port()
    if port <= 0 or not method or not url:
        return None
    out_headers = _gateway_cookies(headers, cookies)
    full = url
    if params:
        full += ("&" if "?" in full else "?") + urlencode(params, doseq=True)
    body = None
    if json_body is not None:
        body = json.dumps(json_body, ensure_ascii=False)
        out_headers.setdefault("Content-Type", "application/json")
    elif isinstance(data, (dict, list, tuple)):
        body = urlencode(data, doseq=True)
        out_headers.setdefault("Content-Type", "application/x-www-form-urlencoded")
    elif isinstance(data, str):
        body = data
    gw = "http://127.0.0.1:%d/webResource?url=%s" % (port, quote(full, safe=""))
    if out_headers:
        gw += "&headers=" + quote(json.dumps(out_headers), safe="")
    if body is not None:
        gw += "&body=" + quote(body, safe="")
    rsp = requests.request(method.upper(), gw, timeout=timeout or GATEWAY_TIMEOUT)
    # 网关透传上游状态码；body 已是完整内容，不再解一次码
    return _gateway_response(rsp.text, rsp.status_code,
                             rsp.headers.get("Content-Type"), url)


class Spider(metaclass=ABCMeta):
    _instance = None

    def __init__(self):
            self.extend = ''

    def __new__(cls, *args, **kwargs):
        if cls._instance:
            return cls._instance
        else:
            cls._instance = super().__new__(cls)
            return cls._instance

    @abstractmethod
    def init(self, extend=""):
        pass

    def homeContent(self, filter):
        pass

    def homeVideoContent(self):
        pass

    def categoryContent(self, tid, pg, filter, extend):
        pass

    def detailContent(self, ids):
        pass

    def searchContent(self, key, quick, pg="1"):
        pass

    def playerContent(self, flag, id, vipFlags):
        pass

    def liveContent(self, url):
        pass

    def localProxy(self, param):
        pass

    def isVideoFormat(self, url):
        pass

    def manualVideoCheck(self):
        pass

    def action(self, action):
        pass

    def destroy(self):
        pass

    def getName(self):
        pass

    def getDependence(self):
        return []

    def loadSpider(self, name):
        return self.loadModule(name).Spider()

    def loadModule(self, name):
        cache_dir = Python.getPlatform().getApplication().getCacheDir().getAbsolutePath()
        path = os.path.join(os.path.join(cache_dir, 'py'),  f'{name}.py')
        return SourceFileLoader(name, path).load_module()

    def regStr(self, reg, src, group=1):
        m = re.search(reg, src)
        src = ''
        if m:
            src = m.group(group)
        return src

    def removeHtmlTags(self, src):
        clean = re.compile('<.*?>')
        return re.sub(clean, '', src)

    def cleanText(self, src):
        clean = re.sub('[\U0001F600-\U0001F64F\U0001F300-\U0001F5FF\U0001F680-\U0001F6FF\U0001F1E0-\U0001F1FF]', '', src)
        return clean

    def fetch(self, url, params=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        if _gateway_wanted(self.extend):
            try:
                rsp = gateway_request("GET", url, headers=headers, cookies=cookies,
                                      params=params, timeout=timeout)
                if rsp is not None:
                    return rsp
            except Exception:
                pass
        rsp = requests.get(url, params=params, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def post(self, url, params=None, data=None, json=None, cookies=None, headers=None, timeout=5, verify=True, stream=False, allow_redirects = True):
        if _gateway_wanted(self.extend):
            try:
                rsp = gateway_request("POST", url, headers=headers, cookies=cookies,
                                      params=params, data=data, json_body=json,
                                      timeout=timeout)
                if rsp is not None:
                    return rsp
            except Exception:
                pass
        rsp = requests.post(url, params=params, data=data, json=json, cookies=cookies, headers=headers, timeout=timeout, verify=verify, stream=stream, allow_redirects=allow_redirects)
        rsp.encoding = 'utf-8'
        return rsp

    def html(self, content):
        return etree.HTML(content)

    def str2json(str):
        return json.loads(str)

    def json2str(str):
        return json.dumps(str, ensure_ascii=False)
    
    def getProxyUrl(self, local=True):
        return f'{Proxy.getUrl(local)}?do=py'

    def log(self, msg):
        if isinstance(msg, dict) or isinstance(msg, list):
            print(json.dumps(msg, ensure_ascii=False))
        else:
            print(f'{msg}')

    def getCache(self, key):
        value = self.fetch(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=get&key={key}', timeout=5).text
        if len(value) > 0:
            if value.startswith('{') and value.endswith('}') or value.startswith('[') and value.endswith(']'):
                value = json.loads(value)
                if type(value) == dict:
                    if not 'expiresAt' in value or value['expiresAt'] >= int(time.time()):
                        return value
                    else:
                        self.delCache(key)
                        return None
            return value
        else:
            return None

    def setCache(self, key, value):
        if type(value) in [int, float]:
            value = str(value)
        if len(value) > 0:
            if type(value) == dict or type(value) == list:
                value = json.dumps(value, ensure_ascii=False)
        r = self.post(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=set&key={key}', data={"value": value}, timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'

    def delCache(self, key):
        r = self.fetch(f'http://127.0.0.1:{Proxy.getPort()}/cache?do=del&key={key}', timeout=5)
        return 'succeed' if r.status_code == 200 else 'failed'
