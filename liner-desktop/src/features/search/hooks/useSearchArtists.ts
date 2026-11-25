import type { ApiArtist, ApiSearchItem } from "@/shared/api";
import {
  useSearchSection,
  type UseSearchSectionOptions,
} from "./useSearchSection";

export type UseSearchArtistsOptions = UseSearchSectionOptions;

export interface UseSearchArtistsResult {
  artists: ApiArtist[];
  loading: boolean;
  hasLoaded: boolean;
  error: Error | null;
  partialWarning?: string;
  runNow: () => void;
}

const mapArtists = (items: ApiSearchItem[]): ApiArtist[] =>
  items.filter((it): it is ApiArtist => it.type === "artist");

export function useSearchArtists(
  query: string,
  options: UseSearchArtistsOptions = {},
): UseSearchArtistsResult {
  const { items, loading, hasLoaded, error, partialWarning, runNow } =
    useSearchSection(query, "artist", mapArtists, options);

  return { artists: items, loading, hasLoaded, error, partialWarning, runNow };
}
