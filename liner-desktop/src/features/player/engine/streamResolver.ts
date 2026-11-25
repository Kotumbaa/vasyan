import {
  isYoutubeSignedIn,
  prefersAccountPlayback,
  resolveDirectAudioStream,
  openTvAudioStream,
  resolveTvAudioStream,
  STREAMS_REFUSED_MESSAGE,
  toVideoId,
} from "@/shared/youtube";
import {
  isOfflineMode,
  OFFLINE_UNAVAILABLE_MESSAGE,
} from "@/features/connectivity/store/offlineModeStore";
import {
  resolveSoundCloudStream,
  searchSoundCloudTracks,
  toSoundCloudTrackId,
} from "@/shared/media/soundcloud";
import { resolveLinerStream } from "@/shared/media/liner/linerPlayback";
import { isLocalTrackId, localTrackAudioUrl } from "@/shared/media/local";
import { savedAudioUrl, savedTrackRecord } from "@/shared/media/saved";
import { isAbortError } from "@/shared/utils/abort";
import { log } from "@/shared/utils/logger";
import { rememberStreamOrigin } from "./nowPlayingLog";
import {
  fromInnertubeStream,
  fromLinerStream,
  fromSoundCloudStream,
  fromTvProgressiveStream,
  fromTvStream,
  type PlayableTrack,
  type PlaybackStream,
} from "./playbackStream";
import { findYoutubeStandIn } from "./youtubeStandIn";
import { findExactSongVersion } from "./exactSongVersion";
import { appAllowsAccountPlayback } from "./accountPlayback";

export interface ResolveStreamOptions {
  progressive?: boolean;
}

export type {
  BufferedPlaybackStream,
  PlayableTrack,
  PlaybackStream,
  UrlPlaybackStream,
} from "./playbackStream";

async function resolveTvAudio(
  videoId: string,
  progressive: boolean,
  signal?: AbortSignal,
): Promise<PlaybackStream> {
  if (progressive)
    return fromTvProgressiveStream(
      await openTvAudioStream(videoId, { signal }),
    );
  return fromTvStream(await resolveTvAudioStream(videoId, { signal }));
}

async function resolveYoutubeAudio(
  videoId: string,
  progressive: boolean,
  signal?: AbortSignal,
): Promise<PlaybackStream> {
  let accountFailure: string | null = null;
  if (
    isYoutubeSignedIn() &&
    prefersAccountPlayback() &&
    (await appAllowsAccountPlayback())
  ) {
    try {
      const stream = await resolveTvAudio(videoId, progressive, signal);
      log("cyan", "playback", `resolved youtube tv audio for ${videoId}`);
      return stream;
    } catch (error) {
      if (isAbortError(error, signal)) throw error;
      accountFailure = error instanceof Error ? error.message : String(error);
      log("yellow", "playback", `youtube tv playback failed: ${String(error)}`);
    }
  }
  try {
    return fromInnertubeStream(
      await resolveDirectAudioStream(videoId, { signal }),
    );
  } catch (error) {
    if (accountFailure && error instanceof Error)
      error.message = `${error.message} Signed-in TV: ${accountFailure}`;
    throw error;
  }
}

async function resolveYoutubeStandIn(
  track: PlayableTrack,
  progressive: boolean,
  signal?: AbortSignal,
): Promise<PlaybackStream> {
  const videoId = await findYoutubeStandIn(track, signal);
  if (!videoId) throw new Error("No matching YouTube track found");
  log(
    "cyan",
    "playback",
    `resolved youtube stand-in ${videoId} for "${track.title}"`,
  );
  const stream = await resolveYoutubeAudio(videoId, progressive, signal);
  stream.standIn = true;
  return stream;
}

async function resolveSoundCloud(
  track: PlayableTrack,
  progressive: boolean,
  signal?: AbortSignal,
): Promise<PlaybackStream> {
  try {
    return fromSoundCloudStream(
      await resolveSoundCloudStream(track.id, { signal }),
    );
  } catch (soundCloudError) {
    if (isAbortError(soundCloudError, signal)) throw soundCloudError;
    log(
      "yellow",
      "playback",
      `soundcloud resolution failed, searching youtube: ${String(soundCloudError)}`,
    );
    try {
      return await resolveYoutubeStandIn(track, progressive, signal);
    } catch (standInError) {
      if (isAbortError(standInError, signal)) throw standInError;
      throw soundCloudError;
    }
  }
}

function tokenizeText(value: string): string[] {
  return value
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, " ")
    .split(/\s+/)
    .filter((token) => token.length >= 2);
}

async function resolveSoundCloudStandIn(
  track: PlayableTrack,
  signal?: AbortSignal,
): Promise<PlaybackStream | null> {
  const query = [track.title, track.artists].filter(Boolean).join(" ").trim();
  if (!query) return null;
  const hits = await searchSoundCloudTracks(query, signal);
  if (hits.length === 0) return null;

  const titleTokens = tokenizeText(track.title);
  const targetMs = track.durationMs > 0 ? track.durationMs : 0;
  const scored = hits
    .map((hit) => {
      const candidateText = `${hit.track.title} ${hit.uploader} ${hit.publisherArtist ?? ""}`.toLowerCase();
      const titleMatch =
        titleTokens.length === 0 ||
        titleTokens.some((token) => candidateText.includes(token));
      const hasDuration = hit.track.durationMs > 0;
      const diffMs =
        targetMs > 0 && hasDuration
          ? Math.abs(hit.track.durationMs - targetMs)
          : targetMs > 0
            ? Number.POSITIVE_INFINITY
            : 0;
      return { hit, titleMatch, diffMs };
    })
    .filter(({ titleMatch, diffMs }) => titleMatch && (targetMs === 0 || diffMs <= 25_000))
    .sort((a, b) => a.diffMs - b.diffMs);

  const chosen = scored[0]?.hit;
  if (!chosen) return null;
  log(
    "cyan",
    "playback",
    `resolved soundcloud stand-in ${chosen.track.id} for "${track.title}"`,
  );
  const stream = fromSoundCloudStream(
    await resolveSoundCloudStream(chosen.track.id, { signal }),
  );
  stream.standIn = true;
  return stream;
}

async function resolveYoutubeTrack(
  track: PlayableTrack,
  rowVideoId: string,
  progressive: boolean,
  signal?: AbortSignal,
): Promise<PlaybackStream> {
  const songId = track.audioVariant
    ? await findExactSongVersion(track, rowVideoId, signal)
    : null;
  if (songId)
    log(
      "cyan",
      "playback",
      `album row ${rowVideoId} is a ${track.audioVariant}, playing the song ${songId}`,
    );
  const videoId = songId ?? rowVideoId;
  const liner = await resolveLinerStream(videoId, signal);
  if (liner) {
    log("cyan", "playback", `resolved liner audio (r2) for "${track.title}"`);
    return fromLinerStream(liner, track);
  }
  try {
    return await resolveYoutubeAudio(videoId, progressive, signal);
  } catch (youtubeError) {
    if (isAbortError(youtubeError, signal)) throw youtubeError;
    const message =
      youtubeError instanceof Error ? youtubeError.message : String(youtubeError);
    const refused = message.startsWith(STREAMS_REFUSED_MESSAGE);

    if (songId && songId !== rowVideoId) {
      try {
        log(
          "yellow",
          "playback",
          `exact song ${songId} failed (${message}), retrying row ${rowVideoId}`,
        );
        return await resolveYoutubeAudio(rowVideoId, progressive, signal);
      } catch (rowError) {
        if (isAbortError(rowError, signal)) throw rowError;
      }
    }

    if (!refused) {
      try {
        const standInId = await findYoutubeStandIn(track, signal);
        if (standInId && standInId !== videoId && standInId !== rowVideoId) {
          log(
            "yellow",
            "playback",
            `youtube ${videoId} failed (${message}), trying stand-in ${standInId}`,
          );
          const stream = await resolveYoutubeAudio(standInId, progressive, signal);
          stream.standIn = true;
          return stream;
        }
      } catch (standInError) {
        if (isAbortError(standInError, signal)) throw standInError;
      }
    }

    try {
      const scStream = await resolveSoundCloudStandIn(track, signal);
      if (scStream) return scStream;
    } catch (scError) {
      if (isAbortError(scError, signal)) throw scError;
    }

    throw youtubeError;
  }
}

export async function resolvePlaybackStream(
  track: PlayableTrack,
  signal?: AbortSignal,
  options: ResolveStreamOptions = {},
): Promise<PlaybackStream> {
  const stream = await resolveStream(track, signal, options);
  rememberStreamOrigin(track.id, stream);
  return stream;
}

async function resolveStream(
  track: PlayableTrack,
  signal?: AbortSignal,
  options: ResolveStreamOptions = {},
): Promise<PlaybackStream> {
  const progressive = options.progressive ?? false;
  if (isLocalTrackId(track.id))
    return {
      url: localTrackAudioUrl(track.id),
      source: "local",
      expiresAtMs: null,
      mimeType: "",
      codec: "local",
      bitrate: 0,
    };
  const saved = savedTrackRecord(track.id);
  if (saved)
    return {
      url: savedAudioUrl(saved),
      expiresAtMs: null,
      mimeType: saved.mimeType,
      codec: "local",
      bitrate: 0,
    };
  if (isOfflineMode()) throw new Error(OFFLINE_UNAVAILABLE_MESSAGE);
  const resolve = (bounded: AbortSignal | undefined) => {
    if (toSoundCloudTrackId(track.id))
      return resolveSoundCloud(track, progressive, bounded);
    const videoId = toVideoId(track.id);
    if (videoId)
      return resolveYoutubeTrack(track, videoId, progressive, bounded);
    return resolveYoutubeStandIn(track, progressive, bounded);
  };
  return progressive ? withResolveTimeout(signal, resolve) : resolve(signal);
}

export const RESOLVE_TIMEOUT_MS = 25_000;
export const RESOLVE_TIMEOUT_MESSAGE =
  "Timed out looking for a playable stream";

export async function withResolveTimeout<T>(
  signal: AbortSignal | undefined,
  run: (signal: AbortSignal) => Promise<T>,
  timeoutMs = RESOLVE_TIMEOUT_MS,
): Promise<T> {
  const controller = new AbortController();
  const timedOut = new Error(RESOLVE_TIMEOUT_MESSAGE);
  const forward = () => controller.abort(signal?.reason);
  if (signal?.aborted) forward();
  else signal?.addEventListener("abort", forward, { once: true });
  let timer: ReturnType<typeof setTimeout> | undefined;
  const deadline = new Promise<never>((_, reject) => {
    timer = setTimeout(() => {
      controller.abort(timedOut);
      reject(timedOut);
    }, timeoutMs);
  });
  try {
    return await Promise.race([run(controller.signal), deadline]);
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener("abort", forward);
  }
}
