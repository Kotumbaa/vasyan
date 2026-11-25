-- Liner Cloudflare Worker + D1 Schema

CREATE TABLE IF NOT EXISTS users (
  id TEXT PRIMARY KEY,
  email TEXT NOT NULL UNIQUE COLLATE NOCASE,
  username TEXT NOT NULL UNIQUE COLLATE NOCASE,
  display_name TEXT NOT NULL,
  password_hash TEXT NOT NULL,
  password_salt TEXT NOT NULL,
  avatar_url TEXT,
  bio TEXT,
  is_public INTEGER NOT NULL DEFAULT 1,
  show_activity INTEGER NOT NULL DEFAULT 1,
  show_history INTEGER NOT NULL DEFAULT 1,
  show_liked_tracks INTEGER NOT NULL DEFAULT 1,
  participate_leaderboards INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  token_hash TEXT NOT NULL UNIQUE,
  expires_at TEXT NOT NULL,
  revoked_at TEXT,
  created_at TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token_hash ON refresh_tokens(token_hash);

CREATE TABLE IF NOT EXISTS liked_tracks (
  user_id TEXT NOT NULL,
  track_id TEXT NOT NULL,
  track_snapshot TEXT NOT NULL,
  liked_at TEXT NOT NULL,
  PRIMARY KEY (user_id, track_id),
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_liked_tracks_user_liked_at ON liked_tracks(user_id, liked_at DESC);

CREATE TABLE IF NOT EXISTS playlists (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  title TEXT NOT NULL,
  description TEXT,
  is_public INTEGER NOT NULL DEFAULT 0,
  revision INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_playlists_user_id ON playlists(user_id, updated_at DESC);

CREATE TABLE IF NOT EXISTS playlist_items (
  id TEXT PRIMARY KEY,
  playlist_id TEXT NOT NULL,
  track_id TEXT NOT NULL,
  track_snapshot TEXT NOT NULL,
  position INTEGER NOT NULL,
  added_at TEXT NOT NULL,
  FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_playlist_items_playlist_pos ON playlist_items(playlist_id, position ASC, added_at ASC);

CREATE TABLE IF NOT EXISTS playback_history (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL,
  playback_session_id TEXT NOT NULL,
  listening_session_id TEXT NOT NULL,
  track_id TEXT NOT NULL,
  track_snapshot TEXT NOT NULL,
  played_duration_ms INTEGER NOT NULL DEFAULT 0,
  track_duration_ms INTEGER,
  completion_rate REAL NOT NULL DEFAULT 0,
  completed INTEGER NOT NULL DEFAULT 0,
  skipped INTEGER NOT NULL DEFAULT 0,
  loop_count INTEGER NOT NULL DEFAULT 0,
  has_seek_backward INTEGER NOT NULL DEFAULT 0,
  context_type TEXT,
  context_id TEXT,
  listened_at TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_playback_history_user_listened ON playback_history(user_id, listened_at DESC);
CREATE INDEX IF NOT EXISTS idx_playback_history_track_listened ON playback_history(track_id, listened_at DESC);

CREATE TABLE IF NOT EXISTS daily_mix_cache (
  user_id TEXT PRIMARY KEY,
  payload TEXT NOT NULL,
  generated_at TEXT NOT NULL,
  FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
