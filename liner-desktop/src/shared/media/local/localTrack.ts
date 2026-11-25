import type { Track } from "@/shared/types";
import { localTrackCoverUrl, toLocalTrackId } from "./localTrackIds";

export interface LocalTrackRecord {
  id: string;
  title: string;
  artist: string;
  album?: string;
  durationMs: number;
  fileName: string;
  audioFile: string;
  mimeType: string;
  coverFile?: string;
  byteSize: number;
  addedAt: number;
}

export interface LocalLibraryUsage {
  count: number;
  bytes: number;
}

export interface LocalImportResult {
  added: LocalTrackRecord[];
  duplicates: number;
  failed: string[];
}

export function localRecordToTrack(record: LocalTrackRecord): Track {
  return {
    id: toLocalTrackId(record.id),
    title: record.title,
    artists: record.artist,
    coverUrl: record.coverFile ? localTrackCoverUrl(record.id) : "",
    durationMs: record.durationMs,
    playCount: 0,
  };
}
