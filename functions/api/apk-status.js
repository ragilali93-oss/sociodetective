/**
 * Cloudflare Pages Function: /api/apk-status
 * Memverifikasi keberadaan file SocioDetective.apk secara nyata pada aset hosting
 * atau URL eksternal yang dikonfigurasi di version.json.
 */

function jsonResponse(payload, status = 200) {
  return new Response(JSON.stringify(payload, null, 2), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store, no-cache, must-revalidate',
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET, HEAD, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type'
    }
  });
}

export async function onRequest(context) {
  const { request, env } = context;

  if (request.method === 'OPTIONS') {
    return jsonResponse({ ok: true }, 204);
  }

  const reqUrl = new URL(request.url);
  const checkedAt = new Date().toISOString();

  let versionMeta = {
    versionName: '1.0',
    versionCode: 1,
    apkUrl: `${reqUrl.origin}/downloads/SocioDetective.apk`
  };

  try {
    const versionRes = await env.ASSETS.fetch(new URL('/version.json', reqUrl.origin));
    if (versionRes.ok) {
      const parsed = await versionRes.json();
      versionMeta = { ...versionMeta, ...parsed };
    }
  } catch (err) {
    // Gunakan nilai default jika version.json gagal dibaca
  }

  let resolvedApkUrl;
  try {
    resolvedApkUrl = new URL(versionMeta.apkUrl || '/downloads/SocioDetective.apk', reqUrl.origin);
  } catch (err) {
    resolvedApkUrl = new URL('/downloads/SocioDetective.apk', reqUrl.origin);
  }

  const isLocalStaticAsset =
    resolvedApkUrl.origin === reqUrl.origin ||
    resolvedApkUrl.hostname === 'sociodetective.pages.dev';

  let available = false;
  let fileSizeBytes = 0;
  let contentType = '';
  let httpStatus = 404;

  try {
    let checkRes;
    if (isLocalStaticAsset && resolvedApkUrl.pathname) {
      const assetUrl = new URL(resolvedApkUrl.pathname, reqUrl.origin);
      checkRes = await env.ASSETS.fetch(new Request(assetUrl.toString(), { method: 'HEAD' }));
    } else {
      checkRes = await fetch(resolvedApkUrl.toString(), { method: 'HEAD', redirect: 'follow' });
    }

    httpStatus = checkRes.status;
    contentType = (checkRes.headers.get('content-type') || '').toLowerCase();
    fileSizeBytes = Number(checkRes.headers.get('content-length') || 0);

    // Pastikan respons benar-benar file biner/APK dan bukan halaman fallback HTML (mis. 404.html)
    const isHtmlFallback = contentType.includes('text/html');
    available = checkRes.ok && !isHtmlFallback && (fileSizeBytes > 0 || contentType.includes('android.package-archive') || contentType.includes('octet-stream'));
  } catch (err) {
    available = false;
    httpStatus = 502;
  }

  return jsonResponse({
    available,
    status: available ? 'ready' : 'maintenance',
    message: available
      ? 'File SocioDetective.apk tersedia dan siap diunduh.'
      : 'Aplikasi sedang dalam pemeliharaan',
    apkUrl: available ? resolvedApkUrl.toString() : null,
    fileSizeBytes: available ? fileSizeBytes : 0,
    contentType: available ? contentType : null,
    httpStatus,
    versionName: versionMeta.versionName,
    versionCode: versionMeta.versionCode,
    checkedAt
  });
}
