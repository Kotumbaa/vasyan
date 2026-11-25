import {
  errorResponse,
  jsonResponse,
  readJsonObject,
  readOptionalNullableString,
  readOptionalString,
} from "./http";
import type {
  AuthSession,
  AuthUser,
  EdgeKeyResponse,
  Env,
  JwtPayload,
  PowChallenge,
  RequestContext,
  UsernameAvailability,
} from "./types";

const ACCESS_TOKEN_TTL_SECONDS = 60 * 60 * 24 * 7; // 7 days
const REFRESH_TOKEN_TTL_SECONDS = 60 * 60 * 24 * 90; // 90 days
const PBKDF2_ITERATIONS = 100_000;
const USERNAME_REGEX = /^[a-z0-9_]{3,24}$/;
const EMAIL_REGEX = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const DEV_FALLBACK_SECRET = "liner-worker-dev-secret-change-in-production";

interface UserRow {
  id: string;
  email: string;
  username: string;
  display_name: string;
  password_hash: string;
  password_salt: string;
  avatar_url: string | null;
  bio: string | null;
  is_public: number;
  show_activity: number;
  show_history: number;
  show_liked_tracks: number;
  participate_leaderboards: number;
  created_at: string;
  updated_at: string;
}

interface RefreshTokenRow {
  id: string;
  user_id: string;
  token_hash: string;
  expires_at: string;
  revoked_at: string | null;
}

function getJwtSecret(env: Env): string {
  return env.JWT_SECRET?.trim() || DEV_FALLBACK_SECRET;
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (let i = 0; i < bytes.length; i++) {
    binary += String.fromCharCode(bytes[i]);
  }
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}

function fromBase64Url(str: string): Uint8Array {
  const padded = str.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat((4 - (str.length % 4)) % 4);
  const binary = atob(padded);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i);
  }
  return bytes;
}

function toHex(bytes: Uint8Array): string {
  return Array.from(bytes)
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
}

function fromHex(hex: string): Uint8Array {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) {
    diff |= a[i] ^ b[i];
  }
  return diff === 0;
}

async function hashPassword(password: string, saltHex?: string): Promise<{ hash: string; salt: string }> {
  const salt = saltHex ? fromHex(saltHex) : crypto.getRandomValues(new Uint8Array(16));
  const keyMaterial = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(password),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const derived = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      salt,
      iterations: PBKDF2_ITERATIONS,
      hash: "SHA-256",
    },
    keyMaterial,
    256,
  );
  return {
    hash: toHex(new Uint8Array(derived)),
    salt: toHex(salt),
  };
}

async function verifyPassword(password: string, storedHash: string, storedSalt: string): Promise<boolean> {
  const { hash } = await hashPassword(password, storedSalt);
  return timingSafeEqual(fromHex(hash), fromHex(storedHash));
}

async function sha256Hex(input: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(input));
  return toHex(new Uint8Array(digest));
}

async function getHmacKey(secret: string, usage: "sign" | "verify"): Promise<CryptoKey> {
  return crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    [usage],
  );
}

export async function signJwt(payload: JwtPayload, secret: string): Promise<string> {
  const encoder = new TextEncoder();
  const headerPart = toBase64Url(encoder.encode(JSON.stringify({ alg: "HS256", typ: "JWT" })));
  const payloadPart = toBase64Url(encoder.encode(JSON.stringify(payload)));
  const signingInput = `${headerPart}.${payloadPart}`;
  const key = await getHmacKey(secret, "sign");
  const sig = await crypto.subtle.sign("HMAC", key, encoder.encode(signingInput));
  return `${signingInput}.${toBase64Url(new Uint8Array(sig))}`;
}

export async function verifyJwt(token: string, secret: string): Promise<JwtPayload | null> {
  const parts = token.split(".");
  if (parts.length !== 3) return null;
  const [headerPart, payloadPart, sigPart] = parts;
  try {
    const key = await getHmacKey(secret, "verify");
    const valid = await crypto.subtle.verify(
      "HMAC",
      key,
      fromBase64Url(sigPart),
      new TextEncoder().encode(`${headerPart}.${payloadPart}`),
    );
    if (!valid) return null;
    const payloadJson = new TextDecoder().decode(fromBase64Url(payloadPart));
    const payload = JSON.parse(payloadJson) as JwtPayload;
    const nowSec = Math.floor(Date.now() / 1000);
    if (!payload.sub || typeof payload.exp !== "number" || payload.exp <= nowSec) {
      return null;
    }
    return payload;
  } catch {
    return null;
  }
}

function mapUserRow(row: UserRow): AuthUser {
  return {
    id: row.id,
    email: row.email,
    username: row.username,
    displayName: row.display_name,
    avatarUrl: row.avatar_url,
    bio: row.bio,
    isPublic: row.is_public === 1,
    showActivity: row.show_activity === 1,
    showHistory: row.show_history === 1,
    showLikedTracks: row.show_liked_tracks === 1,
    participateLeaderboards: row.participate_leaderboards === 1,
  };
}

async function issueSession(env: Env, user: AuthUser): Promise<AuthSession> {
  const nowMs = Date.now();
  const nowSec = Math.floor(nowMs / 1000);
  const accessExpSec = nowSec + ACCESS_TOKEN_TTL_SECONDS;
  const refreshExpMs = nowMs + REFRESH_TOKEN_TTL_SECONDS * 1000;
  const secret = getJwtSecret(env);

  const accessToken = await signJwt(
    {
      sub: user.id,
      email: user.email ?? undefined,
      username: user.username ?? undefined,
      iat: nowSec,
      exp: accessExpSec,
      type: "access",
    },
    secret,
  );

  const rawRefresh = `${crypto.randomUUID()}.${toBase64Url(crypto.getRandomValues(new Uint8Array(24)))}`;
  const refreshHash = await sha256Hex(rawRefresh);
  const nowIso = new Date(nowMs).toISOString();
  const refreshExpIso = new Date(refreshExpMs).toISOString();

  await env.DB.prepare(
    `INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at, created_at)
     VALUES (?, ?, ?, ?, ?)`,
  )
    .bind(crypto.randomUUID(), user.id, refreshHash, refreshExpIso, nowIso)
    .run();

  return {
    accessToken,
    refreshToken: rawRefresh,
    accessTokenExpiresAt: new Date(accessExpSec * 1000).toISOString(),
    expiresAt: accessExpSec * 1000,
    user,
  };
}

export async function authenticateRequest(request: Request, env: Env): Promise<AuthUser | null> {
  const authHeader = request.headers.get("Authorization");
  if (!authHeader || !authHeader.startsWith("Bearer ")) {
    return null;
  }
  const token = authHeader.slice(7).trim();
  if (!token) return null;

  const payload = await verifyJwt(token, getJwtSecret(env));
  if (!payload || payload.type !== "access") return null;

  const row = await env.DB.prepare("SELECT * FROM users WHERE id = ?")
    .bind(payload.sub)
    .first<UserRow>();
  return row ? mapUserRow(row) : null;
}

export async function handleAuthRequest(reqCtx: RequestContext): Promise<Response | null> {
  const { request, env, url, requestId, user } = reqCtx;
  const path = url.pathname;
  const method = request.method.toUpperCase();

  // GET /v1/edge/key
  if (method === "GET" && path === "/v1/edge/key") {
    const key = env.EDGE_KEY?.trim() || "liner-edge-dev-key";
    const rotatesAt = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString();
    const payload: EdgeKeyResponse = { key, rotatesAt };
    return jsonResponse(payload, requestId);
  }

  // POST /v1/auth/challenge
  if (method === "POST" && path === "/v1/auth/challenge") {
    const challenge: PowChallenge = {
      mode: "off",
      algorithm: "argon2id",
      token: "",
      salt: "",
      difficulty: 0,
      memoryKiB: 0,
      iterations: 0,
      parallelism: 0,
      hashLength: 0,
      expiresAt: new Date(Date.now() + 5 * 60 * 1000).toISOString(),
      turnstile: {
        mode: "off",
        siteKey: null,
        edge: false,
      },
    };
    return jsonResponse(challenge, requestId);
  }

  // GET /v1/auth/username-available
  if (method === "GET" && path === "/v1/auth/username-available") {
    const raw = (url.searchParams.get("username") ?? "").trim().toLowerCase();
    const valid = USERNAME_REGEX.test(raw);
    if (!valid) {
      const result: UsernameAvailability = { username: raw, valid: false, available: false };
      return jsonResponse(result, requestId);
    }
    const existing = await env.DB.prepare("SELECT id FROM users WHERE username = ?")
      .bind(raw)
      .first<{ id: string }>();
    const result: UsernameAvailability = {
      username: raw,
      valid: true,
      available: !existing,
    };
    return jsonResponse(result, requestId);
  }

  // POST /v1/auth/register
  if (method === "POST" && path === "/v1/auth/register") {
    const body = await readJsonObject(request);
    if (!body) {
      return errorResponse(400, "Invalid JSON request body.", requestId, "INVALID_BODY");
    }

    const emailField = readOptionalString(body, "email");
    const passwordField = readOptionalString(body, "password");
    const usernameField = readOptionalString(body, "username");
    const displayNameField = readOptionalString(body, "displayName");
    if (
      !emailField.valid ||
      !passwordField.valid ||
      !usernameField.valid ||
      !displayNameField.valid
    ) {
      return errorResponse(400, "Invalid field types in request body.", requestId, "INVALID_BODY");
    }

    const email = (emailField.value ?? "").trim().toLowerCase();
    const password = passwordField.value ?? "";
    const username = (usernameField.value ?? "").trim().toLowerCase();
    const displayName = (displayNameField.value ?? "").trim() || username;

    if (!EMAIL_REGEX.test(email)) {
      return errorResponse(400, "Please enter a valid email address.", requestId, "INVALID_EMAIL");
    }
    if (password.length < 6) {
      return errorResponse(
        400,
        "Password must be at least 6 characters.",
        requestId,
        "INVALID_PASSWORD",
      );
    }
    if (!USERNAME_REGEX.test(username)) {
      return errorResponse(
        400,
        "Username must be 3-24 characters (lowercase letters, numbers, underscores).",
        requestId,
        "INVALID_USERNAME",
      );
    }

    const existingEmail = await env.DB.prepare("SELECT id FROM users WHERE email = ?")
      .bind(email)
      .first<{ id: string }>();
    if (existingEmail) {
      return errorResponse(409, "An account with this email already exists.", requestId, "EMAIL_TAKEN");
    }

    const existingUsername = await env.DB.prepare("SELECT id FROM users WHERE username = ?")
      .bind(username)
      .first<{ id: string }>();
    if (existingUsername) {
      return errorResponse(409, "This username is already taken.", requestId, "USERNAME_TAKEN");
    }

    const id = crypto.randomUUID();
    const nowIso = new Date().toISOString();
    const { hash, salt } = await hashPassword(password);

    await env.DB.prepare(
      `INSERT INTO users (id, email, username, display_name, password_hash, password_salt, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
    )
      .bind(id, email, username, displayName, hash, salt, nowIso, nowIso)
      .run();

    const created = await env.DB.prepare("SELECT * FROM users WHERE id = ?")
      .bind(id)
      .first<UserRow>();
    if (!created) {
      return errorResponse(500, "Failed to create user.", requestId, "INTERNAL_ERROR");
    }

    const session = await issueSession(env, mapUserRow(created));
    return jsonResponse(session, requestId, 201);
  }

  // POST /v1/auth/login
  if (method === "POST" && path === "/v1/auth/login") {
    const body = await readJsonObject(request);
    const emailField = body ? readOptionalString(body, "email") : null;
    const passwordField = body ? readOptionalString(body, "password") : null;
    if (
      !body ||
      !emailField?.valid ||
      !passwordField?.valid ||
      !emailField.value ||
      !passwordField.value
    ) {
      return errorResponse(400, "Email and password are required.", requestId, "INVALID_CREDENTIALS");
    }

    const identifier = emailField.value.trim().toLowerCase();
    const row = await env.DB.prepare("SELECT * FROM users WHERE email = ? OR username = ?")
      .bind(identifier, identifier)
      .first<UserRow>();

    if (!row) {
      return errorResponse(401, "Invalid email or password.", requestId, "INVALID_CREDENTIALS");
    }

    const valid = await verifyPassword(passwordField.value, row.password_hash, row.password_salt);
    if (!valid) {
      return errorResponse(401, "Invalid email or password.", requestId, "INVALID_CREDENTIALS");
    }

    const session = await issueSession(env, mapUserRow(row));
    return jsonResponse(session, requestId);
  }

  // POST /v1/auth/refresh
  if (method === "POST" && path === "/v1/auth/refresh") {
    const body = await readJsonObject(request);
    const rtField = body ? readOptionalString(body, "refreshToken") : null;
    const refreshToken = rtField?.valid ? rtField.value?.trim() : undefined;
    if (!refreshToken) {
      return errorResponse(401, "Missing refresh token.", requestId, "INVALID_REFRESH_TOKEN");
    }

    const tokenHash = await sha256Hex(refreshToken);
    const tokenRow = await env.DB.prepare("SELECT * FROM refresh_tokens WHERE token_hash = ?")
      .bind(tokenHash)
      .first<RefreshTokenRow>();

    if (!tokenRow || tokenRow.revoked_at) {
      return errorResponse(401, "Invalid refresh token.", requestId, "INVALID_REFRESH_TOKEN");
    }

    if (Date.parse(tokenRow.expires_at) <= Date.now()) {
      return errorResponse(401, "Refresh session expired.", requestId, "REFRESH_SESSION_EXPIRED");
    }

    const userRow = await env.DB.prepare("SELECT * FROM users WHERE id = ?")
      .bind(tokenRow.user_id)
      .first<UserRow>();
    if (!userRow) {
      return errorResponse(401, "User not found.", requestId, "INVALID_REFRESH_TOKEN");
    }

    // Re-issue a fresh access token without destructively revoking the refresh token,
    // so a dropped 4G response or app restart mid-refresh never logs the user out.
    const nowSec = Math.floor(Date.now() / 1000);
    const accessExpSec = nowSec + ACCESS_TOKEN_TTL_SECONDS;
    const accessToken = await signJwt(
      {
        sub: userRow.id,
        email: userRow.email,
        username: userRow.username,
        iat: nowSec,
        exp: accessExpSec,
        type: "access",
      },
      getJwtSecret(env),
    );
    const session: AuthSession = {
      accessToken,
      refreshToken,
      accessTokenExpiresAt: new Date(accessExpSec * 1000).toISOString(),
      user: mapUserRow(userRow),
      expiresAt: accessExpSec * 1000,
    };
    return jsonResponse(session, requestId);
  }

  // GET /v1/me
  if (method === "GET" && path === "/v1/me") {
    if (!user) {
      return errorResponse(401, "Unauthorized", requestId, "UNAUTHORIZED");
    }
    return jsonResponse(user, requestId);
  }

  // PATCH /v1/users/me
  if (method === "PATCH" && path === "/v1/users/me") {
    if (!user) {
      return errorResponse(401, "Unauthorized", requestId, "UNAUTHORIZED");
    }
    const body = await readJsonObject(request);
    if (!body) {
      return errorResponse(400, "Invalid JSON request body.", requestId, "INVALID_BODY");
    }

    const usernameField = readOptionalString(body, "username");
    const displayNameField = readOptionalString(body, "displayName");
    const avatarUrlField = readOptionalNullableString(body, "avatarUrl");
    const bioField = readOptionalNullableString(body, "bio");

    if (!usernameField.valid) {
      return errorResponse(400, "Invalid username format.", requestId, "INVALID_USERNAME");
    }
    if (usernameField.value !== undefined) {
      const nextUsername = usernameField.value.trim().toLowerCase();
      if (!USERNAME_REGEX.test(nextUsername)) {
        return errorResponse(400, "Invalid username format.", requestId, "INVALID_USERNAME");
      }
      const existing = await env.DB.prepare("SELECT id FROM users WHERE username = ? AND id != ?")
        .bind(nextUsername, user.id)
        .first<{ id: string }>();
      if (existing) {
        return errorResponse(409, "Username already taken.", requestId, "USERNAME_TAKEN");
      }
    }
    if (!displayNameField.valid) {
      return errorResponse(400, "Invalid displayName format.", requestId, "INVALID_BODY");
    }
    if (!avatarUrlField.valid || !bioField.valid) {
      return errorResponse(400, "Invalid profile field format.", requestId, "INVALID_BODY");
    }

    const current = await env.DB.prepare("SELECT * FROM users WHERE id = ?")
      .bind(user.id)
      .first<UserRow>();
    if (!current) {
      return errorResponse(404, "User not found.", requestId, "NOT_FOUND");
    }

    const nowIso = new Date().toISOString();
    await env.DB.prepare(
      `UPDATE users SET
        username = ?,
        display_name = ?,
        avatar_url = ?,
        bio = ?,
        is_public = ?,
        show_activity = ?,
        show_history = ?,
        show_liked_tracks = ?,
        participate_leaderboards = ?,
        updated_at = ?
       WHERE id = ?`,
    )
      .bind(
        usernameField.value !== undefined
          ? usernameField.value.trim().toLowerCase()
          : current.username,
        displayNameField.value !== undefined
          ? displayNameField.value.trim()
          : current.display_name,
        avatarUrlField.value !== undefined ? avatarUrlField.value : current.avatar_url,
        bioField.value !== undefined ? bioField.value : current.bio,
        body.isPublic !== undefined ? (body.isPublic ? 1 : 0) : current.is_public,
        body.showActivity !== undefined ? (body.showActivity ? 1 : 0) : current.show_activity,
        body.showHistory !== undefined ? (body.showHistory ? 1 : 0) : current.show_history,
        body.showLikedTracks !== undefined
          ? (body.showLikedTracks ? 1 : 0)
          : current.show_liked_tracks,
        body.participateLeaderboards !== undefined
          ? (body.participateLeaderboards ? 1 : 0)
          : current.participate_leaderboards,
        nowIso,
        user.id,
      )
      .run();

    const updated = await env.DB.prepare("SELECT * FROM users WHERE id = ?")
      .bind(user.id)
      .first<UserRow>();
    return jsonResponse(updated ? mapUserRow(updated) : user, requestId);
  }

  return null;
}
