import type { ApiAlbum, ApiSearchItem } from "@/shared/api";
import {
  useSearchSection,
  type UseSearchSectionOptions,
} from "./useSearchSection";

export type UseSearchAlbumsOptions = UseSearchSectionOptions;

export interface UseSearchAlbumsResult {
  albums: ApiAlbum[];
  loading: boolean;
  hasLoaded: boolean;
  error: Error | null;
  partialWarning?: string;
  runNow: () => void;
}

const mapAlbums = (items: ApiSearchItem[]): ApiAlbum[] =>
  items.filter((it): it is ApiAlbum => it.type === "album");

export function useSearchAlbums(
  query: string,
  options: UseSearchAlbumsOptions = {},
): UseSearchAlbumsResult {
  const { items, loading, hasLoaded, error, partialWarning, runNow } =
    useSearchSection(query, "album", mapAlbums, options);

  return { albums: items, loading, hasLoaded, error, partialWarning, runNow };
}
