import { useCallback } from "react";
import {
  useLocation,
  useNavigate,
  useNavigationType,
} from "react-router-dom";

interface RouterHistoryState {
  idx?: number;
}

let maxHistoryIdx = 0;

function readHistoryIndex() {
  const state: RouterHistoryState | null = window.history.state;
  return state?.idx ?? 0;
}

export function useHistoryNavigation() {
  useLocation();
  const navigationType = useNavigationType();
  const navigate = useNavigate();
  const index = readHistoryIndex();

  if (navigationType === "PUSH") {
    maxHistoryIdx = index;
  } else if (index > maxHistoryIdx) {
    maxHistoryIdx = index;
  }

  const canGoBack = index > 0;
  const canGoForward = index < maxHistoryIdx;

  const goBack = useCallback(() => navigate(-1), [navigate]);
  const goForward = useCallback(() => navigate(1), [navigate]);

  return { canGoBack, canGoForward, goBack, goForward };
}
