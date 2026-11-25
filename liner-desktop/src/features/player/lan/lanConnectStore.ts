import { create } from "zustand";
import type { Track } from "@/shared/types";
import { playerEngine } from "../engine/playerEngine";

export interface LanTrackPayload {
  id: string;
  title: string;
  artist: string;
  coverUrl: string;
  durationMs: number;
}

export interface LanRemoteState {
  type: "state";
  deviceId: string;
  deviceName: string;
  platform: string;
  isPlaying: boolean;
  positionMs: number;
  durationMs: number;
  volume?: number;
  isSlave?: boolean;
  updatedAt: number;
  track: LanTrackPayload | null;
}

export interface LanPeerInfo {
  deviceId: string;
  deviceName: string;
  platform: string;
  connectedAt: number;
}

export type LanCommandAction =
  | "play"
  | "pause"
  | "toggle"
  | "next"
  | "prev"
  | "seek"
  | "handoff"
  | "setVolume";

interface LanConnectStoreState {
  peers: LanPeerInfo[];
  remoteStates: Record<string, LanRemoteState>;
  isLocalSlave: boolean;
  lastLocalClaimAt: number;
  setPeers: (peers: LanPeerInfo[]) => void;
  setLocalSlave: (isSlave: boolean) => void;
  upsertRemoteState: (state: LanRemoteState) => void;
  removeRemoteState: (deviceId: string) => void;
}

export const useLanConnectStore = create<LanConnectStoreState>((set) => ({
  peers: [],
  remoteStates: {},
  isLocalSlave: false,
  lastLocalClaimAt: 0,
  setPeers: (peers) => set({ peers }),
  setLocalSlave: (isLocalSlave) =>
    set((prev) => ({
      isLocalSlave,
      lastLocalClaimAt: isLocalSlave ? Date.now() : prev.lastLocalClaimAt,
    })),
  upsertRemoteState: (remote) =>
    set((prev) => {
      const localStatus = playerEngine.getSnapshot().status;
      const localActive =
        localStatus === "playing" || localStatus === "loading";
      const graceElapsed = Date.now() - prev.lastLocalClaimAt > 2500;
      const shouldYield =
        Boolean(remote.isPlaying) && !localActive && graceElapsed;
      return {
        isLocalSlave: shouldYield ? false : prev.isLocalSlave,
        remoteStates: {
          ...prev.remoteStates,
          [remote.deviceId]: remote,
        },
      };
    }),
  removeRemoteState: (deviceId) =>
    set((prev) => {
      const next = { ...prev.remoteStates };
      delete next[deviceId];
      return { remoteStates: next };
    }),
}));

export function getActiveRemoteSlave(): LanRemoteState | null {
  const { peers, remoteStates, isLocalSlave } = useLanConnectStore.getState();
  if (isLocalSlave || peers.length === 0) return null;
  const slaves = Object.values(remoteStates).filter(
    (s) => s.isSlave || s.isPlaying,
  );
  if (slaves.length === 0) return null;
  slaves.sort((a, b) => b.updatedAt - a.updatedAt);
  return slaves[0] ?? null;
}

export function trackToLanPayload(track: Track): LanTrackPayload {
  return {
    id: track.id,
    title: track.title,
    artist: track.artists,
    coverUrl: track.coverUrl ?? "",
    durationMs: Math.max(0, Math.round(track.durationMs || 0)),
  };
}

export function sendRemoteCommand(
  action: LanCommandAction,
  opts?: {
    positionMs?: number;
    volume?: number;
    track?: LanTrackPayload | null;
    queue?: LanTrackPayload[];
    startIndex?: number;
    shuffle?: boolean;
    targetDeviceId?: string;
  },
): void {
  window.linerElectron?.lanConnect?.sendCommand({
    action,
    positionMs: opts?.positionMs,
    volume: opts?.volume,
    track: opts?.track,
    queue: opts?.queue,
    startIndex: opts?.startIndex,
    shuffle: opts?.shuffle,
    targetDeviceId: opts?.targetDeviceId,
  });
}

export function lanTrackToDesktopTrack(remoteTrack: LanTrackPayload): Track {
  return {
    id: remoteTrack.id,
    title: remoteTrack.title,
    artists: remoteTrack.artist,
    coverUrl: remoteTrack.coverUrl,
    durationMs: remoteTrack.durationMs,
    playCount: 0,
  };
}

export async function handoffToDesktop(remote: LanRemoteState): Promise<void> {
  const store = useLanConnectStore.getState();
  store.setLocalSlave(true);
  store.upsertRemoteState({
    ...remote,
    isPlaying: false,
    isSlave: false,
  });
  sendRemoteCommand("handoff", { targetDeviceId: remote.deviceId });
  if (!remote.track) return;
  const track = lanTrackToDesktopTrack(remote.track);
  await playerEngine.playTrack(
    track,
    undefined,
    undefined,
    undefined,
    undefined,
    undefined,
    { forceLocal: true },
  );
  if (remote.positionMs > 0) {
    playerEngine.seek(remote.positionMs, false, { forceLocal: true });
  }
}
