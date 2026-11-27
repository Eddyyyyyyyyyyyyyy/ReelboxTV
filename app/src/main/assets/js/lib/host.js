// =====================================================================
// Reelbox JS 源运行时（drpy2 / TVBox 兼容垫片）
//
// 原生侧只注入了 4 个函数：_http / _pdfh / _pdfa / __log。
// 本文件把 drpy 爬虫用到的 JS 全局补齐。
// =====================================================================

function __stringify(x) {
    if (typeof x === "string") return x;
    try { return JSON.stringify(x); } catch (e) { return String(x); }
}

function log() {
    const parts = [];
    for (let i = 0; i < arguments.length; i++) parts.push(__stringify(arguments[i]));
    __log(parts.join(" "));
}

const console = {
    log: log,
    error: log,
    warn: log,
    info: log,
    debug: log,
};

const VERSION = "reelbox-0.1";
const RKEY = "";

// ---- 网络 ----
function http(url, options = {}) {
    const clean = {};
    for (const k in options) {
        if (typeof options[k] !== "function") clean[k] = options[k];
    }
    const res = _http(String(url), JSON.stringify(clean));
    if (typeof options.complete === "function") {
        options.complete(res);
        return undefined;
    }
    return res;
}

function req(url, options = {}) {
    return http(url, Object.assign({ async: false }, options));
}

function join(url, headers) {
    const r = http(url, { headers: headers || undefined });
    return r && r.content != null ? r.content : "";
}

function getHtml(url) { return join(url); }

// ---- 选择器 ----
function pd(html, rule, base) { return _pdfh(html, rule, base || ""); }
function pdfh(html, rule, base) { return _pdfh(html, rule, base || ""); }
function pdfa(html, rule, base) { return _pdfa(html, rule, base || ""); }
function pdfl(html, rule, base) { return _pdfa(html, rule, base || ""); }

// ---- 代理 / 播放解析（自用版先空实现） ----
function getProxyUrl() { return ""; }
function getProxy() { return ""; }
function proxyParse(o) { return {}; }
function isVideoParse(o) { return null; }
function getParse() { return {}; }
function getPrivateKey() { return ""; }
function getPublicKey() { return ""; }
function fixKey(k) { return k; }
function joinUrl(a, b) {
    try { return new URL(b, a).href; } catch (e) { return b; }
}

// ---- 编解码（自用版先 stub，真实源要用时再接 crypto-js / pako） ----
function base64Encode(s) { return (typeof s === "string") ? encodeURIComponent(s) : s; }
function base64Decode(s) { return s; }
function md5(s) { return ""; }
function aes(s) { return s; }
function rsa(s) { return s; }

const CryptoJS = {
    enc: { Utf8: { stringify: x => x, parse: x => x }, Base64: { stringify: x => x } },
    MD5: () => ({ toString: () => "" }),
    AES: { decrypt: () => ({ toString: () => "" }), encrypt: () => ({ toString: () => "" }) },
    enc2: {},
    mode: {},
    pad: {},
};
const pako = { ungzip: () => new Uint8Array(0), inflate: () => new Uint8Array(0) };
const jinja = { render: (t, o) => t };
const JSON5 = JSON;
function NodeRSA() {}
NodeRSA.prototype.encrypt = () => "";
NodeRSA.prototype.decrypt = () => "";

// drpy 模板里常引用的
function MI(o) { return o; }
