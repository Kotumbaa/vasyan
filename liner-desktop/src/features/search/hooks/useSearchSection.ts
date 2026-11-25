import { useCallback, useEffect, useRef, useState } from "react";
import { api, type ApiSearchItem } from "@/shared/api";

export type SearchSectionKind =
  | "all"
  | "track"
  | "artist"
  | "album"
  | "playlist";

export interface UseSearchSectionOptions {
  enabled?: boolean;
  limit?: number;
  debounceMs?: number;
}

export interface UseSearchSectionResult<T> {
  items: T[];
  loading: boolean;
  hasLoaded: boolean;
  error: Error | null;
  partialWarning?: string;
  runNow: () => void;
}

/**
 * Shared debounced search hook for a single catalog section (`all`, `track`,
 * `artist`, `album`, or `playlist`).
 */
export function useSearchSection<T>(
  query: string,
  kind: SearchSectionKind,
  mapItems: (items: ApiSearchItem[]) => T[],
  options: UseSearchSectionOptions = {},
): UseSearchSectionResult<T> {
  const { enabled = true, debounceMs = 250 } = options;
  const [items, setItems] = useState<T[]>([]);
  const [loading, setLoading] = useState(false);
  const [hasLoaded, setHasLoaded] = useState(false);
  const [error, setError] = useState<Error | null>(null);

  const latestRequestIdRef = useRef(0);
  const lastFetchedQueryRef = useRef("");
  const mapItemsRef = useRef(mapItems);
  mapItemsRef.current = mapItems;

  const trimmed = query.trim();
  const shouldRunSearch = enabled && trimmed.length >= 2;

  const runRequest = useCallback(
    async (requestId: number, q: string) => {
      setLoading(true);
      try {
        const res = await api.search(q, kind);
        if (requestId !== latestRequestIdRef.current) return;
        setItems(mapItemsRef.current(res.items));
        setError(null);
        lastFetchedQueryRef.current = q;
        setHasLoaded(true);
      } catch (err) {
        if (requestId !== latestRequestIdRef.current) return;
        setItems([]);
        setError(err instanceof Error ? err : new Error(String(err)));
        setHasLoaded(true);
      } finally {
        if (requestId === latestRequestIdRef.current) setLoading(false);
      }
    },
    [kind],
  );

  useEffect(() => {
    if (trimmed.length < 2) {
      latestRequestIdRef.current += 1;
      setItems([]);
      setHasLoaded(false);
      setLoading(false);
      setError(null);
      lastFetchedQueryRef.current = "";
      return;
    }

    if (!enabled) {
      setLoading(false);
      return;
    }

    if (lastFetchedQueryRef.current === trimmed && items.length > 0) {
      setLoading(false);
      setHasLoaded(true);
      return;
    }

    setLoading(true);
    const requestId = ++latestRequestIdRef.current;
    const timer = setTimeout(() => {
      if (lastFetchedQueryRef.current === trimmed && items.length > 0) {
        setLoading(false);
        setHasLoaded(true);
        return;
      }
      void runRequest(requestId, trimmed);
    }, debounceMs);

    return () => {
      clearTimeout(timer);
    };
  }, [debounceMs, enabled, items.length, runRequest, trimmed]);

  const runNow = useCallback(() => {
    if (!shouldRunSearch) return;
    const requestId = ++latestRequestIdRef.current;
    void runRequest(requestId, trimmed);
  }, [runRequest, shouldRunSearch, trimmed]);

  return {
    items,
    loading,
    hasLoaded,
    error,
    partialWarning: undefined,
    runNow,
  };
}
