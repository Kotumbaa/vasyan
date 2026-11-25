import type { Track } from "@/shared/types";

/** Extracts a displayable artist string from a track's artist fields. */
export function extractArtistName(track: Track | null | undefined): string {
  if (!track) return "";
  if (typeof track.artists === "string" && track.artists) {
    return track.artists;
  }
  const legacyArtist = (track as { artist?: unknown }).artist;
  if (typeof legacyArtist === "string" && legacyArtist) {
    return legacyArtist;
  }
  if (Array.isArray(track.artistList) && track.artistList.length > 0) {
    return track.artistList
      .map((a) => (typeof a === "string" ? a : a?.name))
      .filter(Boolean)
      .join(", ");
  }
  return "";
}
