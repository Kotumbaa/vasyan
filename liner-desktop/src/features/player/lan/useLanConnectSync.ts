import { useEffect } from "react";
import { playerEngine, type PlayerState } from "../engine/playerEngine";
import {
  lanTrackToDesktopTrack,
  useLanConnectStore,
  type LanTrackPayload,
} from "./lanConnectStore";

function toLanStatePayload(state: PlayerState) {
  const isPlaying = state.status === "playing" || state.status === "loading";
  const track = state.currentTrack
    ? {
        id: state.currentTrack.id,
        title: state.currentTrack.title,
        artist: state.currentTrack.artists,
        coverUrl: state.currentTrack.coverUrl ?? "",
        durationMs: Math.max(
          0,
          Math.round(state.currentTrack.durationMs || state.durationMs || 0),
        ),
      }
    : null;

  return {
    isPlaying,
    positionMs: Math.max(0, Math.round(state.positionMs)),
    durationMs: Math.max(
      0,
      Math.round(state.durationMs || state.currentTrack?.durationMs || 0),
    ),
    volume: Number.isFinite(state.volume)
      ? Math.max(0, Math.min(1, state.volume))
      : 1,
    isSlave: useLanConnectStore.getState().isLocalSlave,
    updatedAt: Date.now(),
    track,
  };
}

async function playRemoteTrack(
  remoteTrack: LanTrackPayload,
  positionMs?: number,
  queue?: LanTrackPayload[],
  startIndex?: number,
) {
  useLanConnectStore.getState().setLocalSlave(true);
  const mappedQueue = queue?.length
    ? queue.map(lanTrackToDesktopTrack)
    : undefined;
  await playerEngine.playTrack(
    lanTrackToDesktopTrack(remoteTrack),
    mappedQueue,
    undefined,
    undefined,
    startIndex,
    undefined,
    { forceLocal: true },
  );
  if (typeof positionMs === "number" && positionMs > 0) {
    playerEngine.seek(positionMs, false, { forceLocal: true });
  }
}

export function useLanConnectSync(): void {
  useEffect(() => {
    const bridge = window.linerElectron?.lanConnect;
    if (!bridge) return;

    const store = useLanConnectStore.getState();

    void bridge.getStatus().then((status) => {
      if (!status) return;
      store.setPeers(status.peers);
      for (const remote of status.remoteStates) {
        store.upsertRemoteState(remote);
      }
    });

    const unsubPeers = bridge.onPeersChanged((peers) => {
      useLanConnectStore.getState().setPeers(peers);
    });

    const unsubRemoteState = bridge.onRemoteState((remote) => {
      useLanConnectStore.getState().upsertRemoteState(remote);
    });

    const unsubRemoteCleared = bridge.onRemoteStateCleared(({ deviceId }) => {
      useLanConnectStore.getState().removeRemoteState(deviceId);
    });

    const unsubCommand = bridge.onCommand((cmd) => {
      switch (cmd.action) {
        case "play":
          if (cmd.track) {
            void playRemoteTrack(
              cmd.track,
              cmd.positionMs,
              cmd.queue,
              cmd.startIndex,
            );
          } else {
            useLanConnectStore.getState().setLocalSlave(true);
            playerEngine.resume({ forceLocal: true });
          }
          break;
        case "pause":
          playerEngine.pause({ forceLocal: true });
          break;
        case "toggle":
          playerEngine.togglePlayPause({ forceLocal: true });
          break;
        case "next":
          void playerEngine.skipNext(false, { forceLocal: true });
          break;
        case "prev":
          void playerEngine.skipPrevious({ forceLocal: true });
          break;
        case "seek":
          if (typeof cmd.positionMs === "number") {
            playerEngine.seek(cmd.positionMs, false, { forceLocal: true });
          }
          break;
        case "handoff":
          if (cmd.track) {
            void playRemoteTrack(
              cmd.track,
              cmd.positionMs,
              cmd.queue,
              cmd.startIndex,
            );
          } else {
            useLanConnectStore.getState().setLocalSlave(false);
            playerEngine.pause({ forceLocal: true });
          }
          break;
        case "setVolume":
          if (typeof cmd.volume === "number" && Number.isFinite(cmd.volume)) {
            playerEngine.setVolume(Math.max(0, Math.min(1, cmd.volume)), {
              forceLocal: true,
            });
          }
          break;
      }
    });

    let lastTrackId: string | null = null;
    let lastIsPlaying = false;
    let lastIsSlave = false;
    let lastDurationMs = 0;
    let lastSentPositionMs = 0;
    let lastVolume: number | undefined = undefined;
    let tickTimer: ReturnType<typeof setInterval> | null = null;

    const pushState = (force = false) => {
      const snapshot = playerEngine.getSnapshot();
      const payload = toLanStatePayload(snapshot);
      const trackId = payload.track?.id ?? null;
      const positionJumped =
        Math.abs(payload.positionMs - lastSentPositionMs) >= 1500;

      if (
        force ||
        trackId !== lastTrackId ||
        payload.isPlaying !== lastIsPlaying ||
        Boolean(payload.isSlave) !== lastIsSlave ||
        payload.durationMs !== lastDurationMs ||
        payload.volume !== lastVolume ||
        positionJumped
      ) {
        lastTrackId = trackId;
        lastIsPlaying = payload.isPlaying;
        lastIsSlave = Boolean(payload.isSlave);
        lastDurationMs = payload.durationMs;
        lastSentPositionMs = payload.positionMs;
        lastVolume = payload.volume;
        bridge.sendState(payload);
      }

      if (snapshot.status === "playing" && !tickTimer) {
        tickTimer = setInterval(() => {
          const current = playerEngine.getSnapshot();
          if (current.status !== "playing") return;
          const nextPayload = toLanStatePayload(current);
          lastSentPositionMs = nextPayload.positionMs;
          bridge.sendState(nextPayload);
        }, 1000);
      } else if (snapshot.status !== "playing" && tickTimer) {
        clearInterval(tickTimer);
        tickTimer = null;
      }
    };

    pushState(true);
    const unsubPlayer = playerEngine.subscribe(() => pushState(false));
    const unsubLan = useLanConnectStore.subscribe(() => pushState(false));

    return () => {
      unsubPeers();
      unsubRemoteState();
      unsubRemoteCleared();
      unsubCommand();
      unsubPlayer();
      unsubLan();
      if (tickTimer) {
        clearInterval(tickTimer);
        tickTimer = null;
      }
    };
  }, []);
}
