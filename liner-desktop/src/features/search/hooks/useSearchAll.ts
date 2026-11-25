import type { ApiSearchItem } from "@/shared/api";
import {
  useSearchSection,
  type UseSearchSectionOptions,
} from "./useSearchSection";

export interface UseSearchAllOptions {
  enabled: boolean;
  limit: number;
  debounceMs: number;
}

export interface UseSearchAllResult {
  items: ApiSearchItem[];
  loading: boolean;
  hasLoaded: boolean;
  partialWarning?: string;
  runNow: () => void;
}

const identity = (items: ApiSearchItem[]) => items;

export function useSearchAll(
  query: string,
  options: UseSearchAllOptions,
): UseSearchAllResult {
  const sectionOptions: UseSearchSectionOptions = {
    enabled: options.enabled,
    limit: options.limit,
    debounceMs: options.debounceMs ?? 320,
  };
  const { items, loading, hasLoaded, partialWarning, runNow } =
    useSearchSection(query, "all", identity, sectionOptions);

  return { items, loading, hasLoaded, partialWarning, runNow };
}
