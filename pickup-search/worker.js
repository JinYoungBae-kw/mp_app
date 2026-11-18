const KEY_URL = "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";
const SEARCH_URL = "https://naverapihub.apigw.ntruss.com/search/v1/local";
const GEOCODE_URL = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode";
const REVERSE_GEOCODE_URL = "https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc";
const RSA_ALGORITHM = "RSASSA-PKCS1-v1_5";
const MAX_PLACES = 1;
const MAX_QUERY_LENGTH = 100;
const MAX_COORDINATE_BODY_BYTES = 256;
const MAX_AUTH_LENGTH = 8_192;
const UPSTREAM_TIMEOUT_MS = 5_000;
const REGION_TIMEOUT_WITH_FALLBACK_MS = 2_500;
const NAVER_COORDINATE_SCALE = 10_000_000;
const HTML_ENTITIES = { amp: "&", apos: "'", gt: ">", lt: "<", nbsp: "\u00a0", quot: '"' };
const encoder = new TextEncoder();
const decoder = new TextDecoder();
let signingKeys = { expiresAt: 0, byId: new Map() };

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const addressLookup = url.pathname === "/address";
    if (!addressLookup && url.pathname !== "/places") return json({ error: "not_found" }, 404);
    const method = addressLookup ? "POST" : "GET";
    if (request.method !== method) return json({ error: "method_not_allowed" }, 405, { Allow: method });

    const query = (url.searchParams.get("q") ?? "").trim();
    if (!addressLookup && (!query || query.length > MAX_QUERY_LENGTH || /[\u0000-\u001f\u007f]/u.test(query))) {
      return json({ error: "invalid_query" }, 400);
    }

    const projectId = env.FIREBASE_PROJECT_ID?.trim();
    if (!projectId) {
      return searchFailure("worker_config", new SearchError("not_configured", 503));
    }

    const signal = AbortSignal.timeout(UPSTREAM_TIMEOUT_MS);
    let stage = "firebase";
    try {
      const uid = await verifiedUid(request.headers.get("Authorization"), projectId, signal);
      if (!uid) return json({ error: "unauthorized" }, 401);
      if (request.headers.get("X-Moadeal-Pickup-Mode") !== "reference") {
        return json({ error: "client_update_required" }, 426);
      }

      const mapsClientId = env.NAVER_MAPS_CLIENT_ID?.trim();
      const mapsClientSecret = env.NAVER_MAPS_CLIENT_SECRET?.trim();
      if (addressLookup) {
        stage = "naver_reverse_geocoding";
        const coordinates = await readCoordinates(request, signal);
        if (!coordinates) return json({ error: "invalid_coordinates" }, 400);
        if (!mapsClientId || !mapsClientSecret) {
          throw new SearchError("maps_not_configured", 503);
        }
        const address = await reverseGeocode(coordinates.lat, coordinates.lng, mapsClientId, mapsClientSecret, signal);
        return json({ address });
      }

      stage = "naver";
      return await searchPlaces(query, env, mapsClientId, mapsClientSecret, signal);
    } catch (error) {
      return searchFailure(stage, error, signal.aborted);
    }
  },
};

async function readCoordinates(request, signal) {
  const contentType = request.headers.get("Content-Type")?.split(";", 1)[0].trim().toLowerCase();
  if (contentType !== "application/json" || !request.body || signal.aborted
    || Number(request.headers.get("Content-Length")) > MAX_COORDINATE_BODY_BYTES) return null;

  const reader = request.body.getReader();
  const bytes = new Uint8Array(MAX_COORDINATE_BODY_BYTES);
  const cancel = () => { void reader.cancel().catch(() => {}); };
  signal.addEventListener("abort", cancel, { once: true });
  let length = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value.byteLength > bytes.length - length) {
        cancel();
        return null;
      }
      bytes.set(value, length);
      length += value.byteLength;
    }
    if (signal.aborted) return null;

    const body = JSON.parse(decoder.decode(bytes.subarray(0, length)));
    if (typeof body?.lat !== "number" || typeof body?.lng !== "number" || !isKoreanCoordinate(body.lat, body.lng)) {
      return null;
    }
    return { lat: body.lat, lng: body.lng };
  } catch {
    return null;
  } finally {
    signal.removeEventListener("abort", cancel);
    reader.releaseLock();
  }
}

async function searchPlaces(query, env, mapsClientId, mapsClientSecret, signal) {
  const clientId = env.NAVER_CLIENT_ID?.trim();
  const clientSecret = env.NAVER_CLIENT_SECRET?.trim();
  const mapsConfigured = Boolean(mapsClientId && mapsClientSecret);
  let places;
  let failure;

  if (clientId && clientSecret) {
    const regionSignal = mapsConfigured
      ? AbortSignal.any([signal, AbortSignal.timeout(REGION_TIMEOUT_WITH_FALLBACK_MS)])
      : signal;
    try {
      places = await searchNaver(query, clientId, clientSecret, regionSignal);
      if (places.some((place) => place.latitude !== undefined)) return json({ provider: "naver", places });
    } catch (error) {
      failure = reportFailure("naver", error, regionSignal.aborted);
    }
  }

  if (mapsConfigured && !signal.aborted) {
    try {
      const mappedPlaces = await geocode(query, mapsClientId, mapsClientSecret, signal);
      if (mappedPlaces.length || (!places && !failure)) {
        return json({ provider: "naver", source: "maps-geocoding", places: mappedPlaces });
      }
    } catch (error) {
      const mapsFailure = reportFailure("naver_geocoding", error, signal.aborted);
      failure ??= mapsFailure;
    }
  }

  if (places) return json({ provider: "naver", places });
  if (failure) return json({ error: failure.code }, failure.status);
  return searchFailure("worker_config", new SearchError("not_configured", 503));
}

function json(body, status = 200, extraHeaders = {}) {
  return Response.json(body, {
    status,
    headers: { "Cache-Control": "no-store", ...extraHeaders },
  });
}

function base64urlBytes(value) {
  const base64 = value.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(base64), (character) => character.charCodeAt(0));
}

async function verifiedUid(authorization, projectId, signal) {
  if (!authorization || authorization.length > MAX_AUTH_LENGTH) return null;
  const parts = /^Bearer ([\w-]+)\.([\w-]+)\.([\w-]+)$/i.exec(authorization);
  if (!parts) return null;
  const [, encodedHeader, encodedClaims, encodedSignature] = parts;

  let header, claims, signature;
  try {
    header = JSON.parse(decoder.decode(base64urlBytes(encodedHeader)));
    claims = JSON.parse(decoder.decode(base64urlBytes(encodedClaims)));
    signature = base64urlBytes(encodedSignature);
  } catch {
    return null;
  }

  const now = Math.floor(Date.now() / 1000);
  if (header?.alg !== "RS256" || typeof header.kid !== "string" || !header.kid
    || claims?.aud !== projectId || claims.iss !== `https://securetoken.google.com/${projectId}`
    || typeof claims.sub !== "string" || !claims.sub || claims.sub.length > 128
    || !Number.isSafeInteger(claims.exp) || claims.exp <= now
    || !Number.isSafeInteger(claims.iat) || claims.iat > now
    || !Number.isSafeInteger(claims.auth_time) || claims.auth_time > now) {
    return null;
  }

  const key = await publicKey(header.kid, signal);
  if (!key) return null;
  const data = encoder.encode(`${encodedHeader}.${encodedClaims}`);
  return await crypto.subtle.verify(RSA_ALGORITHM, key, signature, data) ? claims.sub : null;
}

async function publicKey(keyId, signal) {
  if (signingKeys.expiresAt <= Date.now()) {
    const response = await fetch(KEY_URL, { signal, redirect: "manual" });
    if (!response.ok) throw new SearchError("authentication_unavailable", 503);

    const data = await response.json();
    const byId = new Map();
    for (const jwk of data.keys) {
      if (jwk.kty === "RSA" && jwk.alg === "RS256" && jwk.use === "sig") byId.set(jwk.kid, { jwk });
    }
    if (!byId.size) throw new SearchError("authentication_unavailable", 503);

    const cacheControl = response.headers.get("Cache-Control") ?? "";
    const maxAge = Number(/(?:^|,)\s*max-age=(\d+)/i.exec(cacheControl)?.[1] ?? 0);
    const age = Number(response.headers.get("Age") ?? 0);
    const expiresAt = Date.now() + Math.max(0, Math.min(maxAge - age, 86_400)) * 1000;
    signingKeys = { byId, expiresAt };
  }

  const entry = signingKeys.byId.get(keyId);
  if (!entry) return null;
  if (!entry.key) {
    entry.key = await crypto.subtle.importKey("jwk", entry.jwk, {
      name: RSA_ALGORITHM,
      hash: "SHA-256",
    }, false, ["verify"]);
  }
  return entry.key;
}

class SearchError extends Error {
  constructor(code, status, details = {}) {
    super(code);
    this.code = code;
    this.status = status;
    this.details = details;
  }
}

function searchFailure(stage, error, timedOut = false) {
  const failure = reportFailure(stage, error, timedOut);
  return json({ error: failure.code }, failure.status);
}

function reportFailure(stage, error, timedOut) {
  const failure = error instanceof SearchError ? error
    : stage === "firebase" ? new SearchError(timedOut ? "firebase_timeout" : "authentication_unavailable", 503)
    : new SearchError(timedOut ? "naver_timeout" : "naver_connection", 502);
  console.warn({ event: "pickup_search_failed", stage, code: failure.code, status: failure.status,
    ...failure.details });
  return failure;
}

async function searchNaver(query, clientId, clientSecret, signal) {
  const result = await fetchNaverJson(SEARCH_URL, {
    query,
    display: String(MAX_PLACES),
    start: "1",
    sort: "random",
    format: "json",
  }, clientId, clientSecret, signal);
  if (!Array.isArray(result?.items)) {
    throw new SearchError("naver_invalid_response", 502);
  }

  return result.items.slice(0, MAX_PLACES).map((item) => {
    const name = plainText(item?.title);
    const address = plainText(item?.roadAddress) || plainText(item?.address);
    const link = sourceLink(item?.link);
    return {
      name,
      address,
      ...(link ? { link } : {}),
      ...naverCoordinates(item?.mapx, item?.mapy),
    };
  });
}

async function geocode(query, clientId, clientSecret, signal) {
  const result = await fetchNaverJson(GEOCODE_URL, {
    query,
    count: String(MAX_PLACES),
  }, clientId, clientSecret, signal);
  if (result?.status !== "OK" || !Array.isArray(result.addresses)) {
    throw new SearchError("naver_invalid_response", 502);
  }

  const places = [];
  for (const item of result.addresses.slice(0, MAX_PLACES)) {
    const latitude = coordinateNumber(item?.y);
    const longitude = coordinateNumber(item?.x);
    const address = plainText(item?.roadAddress) || plainText(item?.jibunAddress);
    if (!address || !isKoreanCoordinate(latitude, longitude)) continue;

    const elements = Array.isArray(item?.addressElements) ? item.addressElements : [];
    const building = elements.find((entry) => Array.isArray(entry?.types) && entry.types.includes("BUILDING_NAME"));
    places.push({ name: plainText(building?.longName) || address, address, latitude, longitude });
  }
  return places;
}

async function reverseGeocode(latitude, longitude, clientId, clientSecret, signal) {
  const result = await fetchNaverJson(REVERSE_GEOCODE_URL, {
    coords: `${longitude},${latitude}`,
    sourcecrs: "epsg:4326",
    orders: "roadaddr,addr",
    output: "json",
  }, clientId, clientSecret, signal);
  if (result?.status?.code === 3) return "";
  if (result?.status?.code !== 0 || !Array.isArray(result.results)) {
    throw new SearchError("naver_invalid_response", 502);
  }

  const road = result.results.find((entry) => entry?.name === "roadaddr");
  const parcel = result.results.find((entry) => entry?.name === "addr");
  return formatReverseAddress(road) || formatReverseAddress(parcel);
}

function formatReverseAddress(result) {
  const province = plainText(result?.region?.area1?.name);
  const city = plainText(result?.region?.area2?.name);
  const town = plainText(result?.region?.area3?.name);
  const land = result?.land;
  const mainNumber = plainText(land?.number1);
  const subNumber = plainText(land?.number2);
  if (!province || !mainNumber || mainNumber === "0") return "";

  const number = subNumber && subNumber !== "0" ? `${mainNumber}-${subNumber}` : mainNumber;
  if (result.name === "roadaddr") {
    const road = plainText(land.name);
    const township = town.endsWith("읍") || town.endsWith("면") ? town : "";
    return road ? [province, city, township, road, number].filter(Boolean).join(" ") : "";
  }

  const village = plainText(result.region?.area4?.name);
  const lotNumber = land.type === "2" ? `산${number}` : number;
  return [province, city, town, village, lotNumber].filter(Boolean).join(" ");
}

async function fetchNaverJson(endpoint, parameters, clientId, clientSecret, signal) {
  const url = new URL(endpoint);
  url.search = new URLSearchParams(parameters);

  const response = await fetch(url, {
    headers: {
      Accept: "application/json",
      "X-NCP-APIGW-API-KEY-ID": clientId,
      "X-NCP-APIGW-API-KEY": clientSecret,
    },
    cache: "no-store",
    redirect: "manual",
    signal,
  });
  if (!response.ok) {
    const details = { upstreamStatus: response.status };
    if (response.status === 429) throw new SearchError("rate_limited", 429, details);
    if (response.status === 401 || response.status === 403) {
      throw new SearchError("provider_configuration", 503, details);
    }
    throw new SearchError("naver_http_error", 502, details);
  }

  try {
    return await response.json();
  } catch (error) {
    if (signal.aborted) throw error;
    throw new SearchError("naver_invalid_response", 502);
  }
}

function naverCoordinates(mapx, mapy) {
  let longitude = coordinateNumber(mapx);
  let latitude = coordinateNumber(mapy);
  if (longitude > 180 && latitude > 90) {
    longitude /= NAVER_COORDINATE_SCALE;
    latitude /= NAVER_COORDINATE_SCALE;
  }
  if (!isKoreanCoordinate(latitude, longitude)) return {};
  return { latitude, longitude };
}

function isKoreanCoordinate(latitude, longitude) {
  return latitude >= 32 && latitude <= 40 && longitude >= 123 && longitude <= 133;
}

function coordinateNumber(value) {
  if (typeof value === "number") return value;
  if (typeof value === "string" && /^\d+(?:\.\d+)?$/.test(value.trim())) return Number(value);
  return NaN;
}

function plainText(value) {
  if (typeof value !== "string") return "";
  return value.replace(/<!--[\s\S]*?-->|<\/?[a-z][^<>]*>/gi, "")
    .replace(/&(#x[0-9a-f]+|#[0-9]+|amp|apos|gt|lt|nbsp|quot);/gi, (match, entity) => {
      if (entity[0] !== "#") return HTML_ENTITIES[entity.toLowerCase()];
      const hex = entity[1].toLowerCase() === "x";
      const codePoint = Number.parseInt(entity.slice(hex ? 2 : 1), hex ? 16 : 10);
      if (codePoint <= 0 || codePoint > 0x10ffff || (codePoint >= 0xd800 && codePoint <= 0xdfff)) return match;
      return String.fromCodePoint(codePoint);
    })
    .replace(/[\u0000-\u001f\u007f]/gu, " ")
    .trim();
}

function sourceLink(value) {
  if (typeof value !== "string" || !/^https?:\/\//i.test(value) || /[\s\\]/u.test(value)) return "";
  try {
    const url = new URL(value);
    return url.hostname && !url.username && !url.password ? value : "";
  } catch {
    return "";
  }
}
