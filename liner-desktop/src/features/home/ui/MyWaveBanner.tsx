import { useState } from "react";
import { PlayFill, PauseFill, RadioFill } from "@mingcute/react";
import { playerEngine, usePlayerStore } from "@/features/player";
import { isWaveContext, getWaveVibeFromContext } from "@/features/player/engine/radioMode";
import type { WaveVibe } from "@/shared/api";
import Button from "@/shared/ui/Button";

const WAVE_VIBES: { id: WaveVibe; label: string }[] = [
  { id: "all", label: "Для меня" },
  { id: "favorite", label: "Любимое" },
  { id: "discover", label: "Незнакомое" },
  { id: "energy", label: "Бодрое" },
  { id: "calm", label: "Спокойное" },
];

export default function MyWaveBanner() {
  const playbackContext = usePlayerStore((s) => s.playbackContext);
  const status = usePlayerStore((s) => s.status);
  const isWaveActive = isWaveContext(playbackContext);
  const activeVibe = getWaveVibeFromContext(playbackContext);
  const [selectedVibe, setSelectedVibe] = useState<WaveVibe>(activeVibe);
  const [starting, setStarting] = useState(false);

  const displayVibe = isWaveActive ? activeVibe : selectedVibe;
  const isWavePlaying = isWaveActive && status === "playing";
  const isLoading = starting || (isWaveActive && status === "loading");

  const handlePlayToggle = async () => {
    if (isWaveActive && activeVibe === displayVibe) {
      playerEngine.togglePlayPause();
      return;
    }
    setStarting(true);
    try {
      await playerEngine.startMyWave(displayVibe);
    } finally {
      setStarting(false);
    }
  };

  const handleSelectVibe = async (vibe: WaveVibe) => {
    setSelectedVibe(vibe);
    if (isWaveActive && vibe !== activeVibe) {
      setStarting(true);
      try {
        await playerEngine.startMyWave(vibe);
      } finally {
        setStarting(false);
      }
    }
  };

  return (
    <div className="mb-[22px] rounded-[16px] bg-fill-subtle p-[16px] transition-colors duration-200 ease-out">
      <div className="flex flex-wrap items-center justify-between gap-[12px]">
        <div className="flex items-center gap-[12px]">
          <div
            className={`flex h-[44px] w-[44px] items-center justify-center rounded-[12px] transition-colors duration-200 ${
              isWaveActive
                ? "bg-accent-primary text-bg-primary"
                : "bg-fill-strong text-text-primary"
            }`}
          >
            <RadioFill className="h-[22px] w-[22px]" />
          </div>
          <div>
            <h2 className="text-[18px] font-[700] leading-[1.2] text-text-primary">
              Моя волна
            </h2>
            <p className="mt-[2px] text-[13px] text-text-secondary">
              {isWaveActive
                ? `Настроение: ${WAVE_VIBES.find((v) => v.id === displayVibe)?.label ?? "Для меня"}`
                : "Бесконечный поток под твой вкус"}
            </p>
          </div>
        </div>

        <Button
          variant="primary"
          onClick={() => void handlePlayToggle()}
          disabled={isLoading}
          className="!h-[38px] !rounded-full px-[18px] text-[13.5px] font-[600]"
        >
          {isWavePlaying ? (
            <PauseFill className="mr-[6px] h-[16px] w-[16px]" />
          ) : (
            <PlayFill className="mr-[6px] h-[16px] w-[16px]" />
          )}
          {isLoading ? "Подбор..." : isWavePlaying ? "Пауза" : "Слушать"}
        </Button>
      </div>

      <div className="mt-[12px] flex flex-wrap gap-[8px]">
        {WAVE_VIBES.map((vibe) => {
          const active = displayVibe === vibe.id;
          return (
            <button
              key={vibe.id}
              type="button"
              onClick={() => void handleSelectVibe(vibe.id)}
              className={`cursor-pointer rounded-[8px] px-[12px] py-[6px] text-[12.5px] font-[500] transition-all duration-150 ease-out active:scale-[0.96] ${
                active
                  ? "bg-accent-primary text-bg-primary font-[600]"
                  : "bg-fill text-text-secondary hover:bg-fill-hover hover:text-text-primary"
              }`}
            >
              {vibe.label}
            </button>
          );
        })}
      </div>
    </div>
  );
}
