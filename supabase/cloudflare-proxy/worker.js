// Cloudflare Worker —— Supabase 反向代理
//
// 为什么需要：
//   部分国内线路（尤其是随身 WiFi 用的 China Mobile / cmnet 出口）会对 TLS
//   ClientHello 里的 SNI 做匹配，命中 *.supabase.co 就直接 RST。表现为 TCP 能
//   连通（80/443 均可达）但 TLS 握手被重置，APP 侧只能等到 connect timeout。
//   已实测与 Cloudflare 自身、IPv6、MTU、DNS、证书均无关。
//
// 做法：
//   APP 侧并行探测「直连 Supabase」与「本 Worker」两个地址，谁先返回就用谁；
//   用本 Worker 时，请求按原样转发到同一个 Supabase 项目，App 侧逻辑零改动。
//   所以 Worker 必须保持路径/方法/请求头/响应体完全透传。
//
// 部署：
//   Cloudflare Dashboard -> Workers & Pages -> Create Worker -> Deploy
//   把生成的 https://<你的子域>.workers.dev 填入 App 的
//   app/src/main/java/com/example/cowdunggame/SupabaseClient.java 里的 PROXY_URL
//
// anon key 本身就是公开的（客户端本来就要带 apikey 头），放在这里是兼容
// 客户端漏带的情况，不构成泄露。

const SUPABASE_ORIGIN = 'https://uihalfuswgilzzhzmgpv.supabase.co';
const ANON_KEY = 'sb_publishable_HaCpd4tIhhaunf8S-b7FoQ_6uBGzbLM';

// 不能原样透传的请求头：Host/长度/编码/链路级头，转发时会算错或报错
const DROP_REQUEST = [
  'host', 'content-length', 'content-encoding', 'accept-encoding',
  'connection', 'keep-alive', 'transfer-encoding', 'upgrade',
  'cf-connecting-ip', 'cf-ipcountry', 'cf-ray', 'cf-visitor',
  'x-forwarded-for', 'x-forwarded-proto',
];

// 响应侧还要额外丢掉这些，否则下游会按错误的编码/长度解析
const DROP_RESPONSE = DROP_REQUEST.concat(['server', 'set-cookie']);

export default {
  async fetch(request) {
    if (request.method === 'OPTIONS') {
      return new Response(null, { status: 204, headers: cors() });
    }

    const incoming = new URL(request.url);
    const target = SUPABASE_ORIGIN + incoming.pathname + incoming.search;

    const headers = new Headers();
    for (const [k, v] of request.headers) {
      if (!DROP_REQUEST.includes(k.toLowerCase())) headers.set(k, v);
    }
headers.set('apikey', ANON_KEY);
    // 刻意不注入 Authorization。Supabase 的发布密钥（sb_publishable_…）不是 JWT，
    // 塞进 Authorization 头会被 PostgREST 判成 PGRST301（Expected 3 parts in JWT）。
    // 未登录请求只靠 apikey 头即可落到 anon 角色；已登录的请求由 App 自带真实 JWT。

    const init = { method: request.method, headers, redirect: 'manual' };
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      init.body = await request.arrayBuffer();
    }

    let upstream;
    try {
      upstream = await fetch(target, init);
    } catch (e) {
      return json({ error: 'upstream_failed', detail: String(e) }, 502);
    }

    const out = new Headers();
    for (const [k, v] of upstream.headers) {
      if (!DROP_RESPONSE.includes(k.toLowerCase())) out.set(k, v);
    }
    for (const [k, v] of Object.entries(cors())) out.set(k, v);

    return new Response(upstream.body, {
      status: upstream.status,
      statusText: upstream.statusText,
      headers: out,
    });
  },
};

function cors() {
  return {
    'Access-Control-Allow-Origin': '*',
    'Access-Control-Allow-Methods': 'GET, POST, PATCH, PUT, DELETE, OPTIONS',
    'Access-Control-Allow-Headers':
      'apikey, Authorization, Content-Type, Prefer, Range, Accept-Profile, Content-Profile, X-Client-Info, X-Supabase-Api-Version',
    'Access-Control-Expose-Headers': '*',
    'Access-Control-Max-Age': '86400',
  };
}

function json(obj, status) {
  return new Response(JSON.stringify(obj), {
    status,
    headers: Object.assign({ 'Content-Type': 'application/json' }, cors()),
  });
}