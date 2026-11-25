import type { ApiPlaylist, ApiSearchItem } from "@/shared/api";
import {
  useSearchSection,
  type UseSearchSectionOptions,
} from "./useSearchSection";

export type UseSearchPlaylistsOptions = UseSearchSectionOptions;

export interface UseSearchPlaylistsResult {
  playlists: ApiPlaylist[];
  loading: boolean;
  hasLoaded: boolean;
  error: Error | null;
  partialWarning?: string;
  runNow: () => void;
}

const mapPlaylists = (items: ApiSearchItem[]): ApiPlaylist[] =>
  items.filter((it): it is ApiPlaylist => it.type === "playlist");

export function useSearchPlaylists(
  query: string,
  options: UseSearchPlaylistsOptions = {},
): UseSearchPlaylistsResult {
  const { items, loading, hasLoaded, error, partialWarning, runNow } =
    useSearchSection(query, "playlist", mapPlaylists, options);

  return {
    playlists: items,
    loading,
    hasLoaded,
    error,
    partialWarning,
    runNow,
  };
}
