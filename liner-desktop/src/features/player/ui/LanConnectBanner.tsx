import { memo, useMemo } from "react";
import { Pause, Play, SkipNext, SkipPrevious } from "@solar-icons/react";
import {
  handoffToDesktop,
  sendRemoteCommand,
  useLanConnectStore,
  type LanRemoteState,
} from "../lan/lanConnectStore";
import { usePlayerState } from "../hooks/usePlayerState";

function pickActiveRemote(
  remoteStates: Record<string, LanRemoteState>,
): LanRemoteState | null {
  const list = Object.values(remoteStates).filter(
    (s) => s.track !== null || s.isSlave,
  );
  if (list.length === 0) return null;
  list.sort((a, b) => {
    if (Boolean(a.isSlave) !== Boolean(b.isSlave)) return a.isSlave ? -1 : 1;
    if (a.isPlaying !== b.isPlaying) return a.isPlaying ? -1 : 1;
    return b.updatedAt - a.updatedAt;
  });
  return list[0] ?? null;
}

export const LanConnectBanner = memo(function LanConnectBanner() {
  const remoteStates = useLanConnectStore((s) => s.remoteStates);
  const isLocalSlave = useLanConnectStore((s) => s.isLocalSlave);
  const player = usePlayerState();
  const isDesktopPlaying =
    player.status === "playing" || player.status === "loading";

  const activeRemote = useMemo(
    () => pickActiveRemote(remoteStates),
    [remoteStates],
  );

  if (!activeRemote || !activeRemote.track) return null;
  if (isLocalSlave) return null;
  if (!activeRemote.isSlave && !activeRemote.isPlaying && isDesktopPlaying)
    return null;

  const { deviceName, isPlaying, track, deviceId } = activeRemote;

  return (
    <div
      className="mb-[8px] flex h-[40px] w-full items-center justify-between gap-[12px] rounded-2xl border-[0.5px] border-border-tertiary bg-bg-primary/90 px-[14px] backdrop-blur-md"
      onPointerDown={(e) => e.stopPropagation()}
    >
      <div className="flex min-w-0 items-center gap-[8px]">
        <span
          className={`h-[7px] w-[7px] shrink-0 rounded-full ${
            isPlaying ? "bg-emerald-400" : "bg-text-tertiary"
          }`}
        />
        <span className="shrink-0 text-[12px] font-[500] text-text-secondary">
          {isPlaying ? "Играет на" : "На паузе на"} {deviceName}
        </span>
        <span className="text-text-tertiary">•</span>
        <span className="min-w-0 truncate text-[12px] text-text-primary">
          {track.title}
          {track.artist ? (
            <span className="text-text-tertiary"> — {track.artist}</span>
          ) : null}
        </span>
      </div>

      <div className="flex shrink-0 items-center gap-[6px]">
        <button
          type="button"
          aria-label="Предыдущий трек"
          className="inline-flex h-[26px] w-[26px] items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-bg-elevated hover:text-text-primary cursor-pointer"
          onClick={() =>
            sendRemoteCommand("prev", { targetDeviceId: deviceId })
          }
        >
          <SkipPrevious size={14} weight="Bold" />
        </button>
        <button
          type="button"
          aria-label={isPlaying ? "Пауза" : "Воспроизвести"}
          className="inline-flex h-[26px] w-[26px] items-center justify-center rounded-full bg-btn-primary-bg text-btn-primary-text transition-transform hover:scale-105 active:scale-95 cursor-pointer"
          onClick={() =>
            sendRemoteCommand("toggle", { targetDeviceId: deviceId })
          }
        >
          {isPlaying ? (
            <Pause size={13} weight="Bold" />
          ) : (
            <Play size={13} weight="Bold" />
          )}
        </button>
        <button
          type="button"
          aria-label="Следующий трек"
          className="inline-flex h-[26px] w-[26px] items-center justify-center rounded-full text-text-secondary transition-colors hover:bg-bg-elevated hover:text-text-primary cursor-pointer"
          onClick={() =>
            sendRemoteCommand("next", { targetDeviceId: deviceId })
          }
        >
          <SkipNext size={14} weight="Bold" />
        </button>
        <button
          type="button"
          className="ml-[4px] rounded-full border-[0.5px] border-border-tertiary bg-bg-elevated px-[10px] py-[4px] text-[11px] font-[500] text-text-primary transition-colors hover:bg-fill-strong cursor-pointer"
          onClick={() => {
            void handoffToDesktop(activeRemote);
          }}
        >
          Слушать на ПК
        </button>
      </div>
    </div>
  );
});
