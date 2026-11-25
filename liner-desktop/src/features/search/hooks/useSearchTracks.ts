import { toClientTrack, type ApiSearchItem, type ApiTrack } from "@/shared/api";
import type { Track } from "@/shared/types";
import {
  useSearchSection,
  type UseSearchSectionOptions,
} from "./useSearchSection";

export type UseSearchTracksOptions = UseSearchSectionOptions;

export interface UseSearchTracksResult {
  tracks: Track[];
  loading: boolean;
  hasLoaded: boolean;
  error: Error | null;
  partialWarning?: string;
  runNow: () => void;
}

const mapTracks = (items: ApiSearchItem[]): Track[] =>
  items
    .filter((it): it is ApiTrack & { type: "track" } => it.type === "track")
    .map(toClientTrack);

export function useSearchTracks(
  query: string,
  options: UseSearchTracksOptions = {},
): UseSearchTracksResult {
  const { items, loading, hasLoaded, error, partialWarning, runNow } =
    useSearchSection(query, "track", mapTracks, options);

  return { tracks: items, loading, hasLoaded, error, partialWarning, runNow };
}
