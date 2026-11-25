import { useEffect, useState } from "react";
import {
  extractDarkDominantRgb,
  type DominantDarkColor,
} from "@/shared/lib/color";

export type { DominantDarkColor };

type CoverElementLoader = (url: string) => Promise<HTMLImageElement | null>;

const defaultCoverLoader: CoverElementLoader = (url) =>
  new Promise((resolve) => {
    if (typeof Image === "undefined") {
      resolve(null);
      return;
    }
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = url;
  });

let coverElementLoader: CoverElementLoader = defaultCoverLoader;

export function registerCoverElementLoader(loader: CoverElementLoader): void {
  coverElementLoader = loader;
}

const MAX_COLOR_CACHE = 200;
const colorCache = new Map<string, DominantDarkColor>();

function rememberColor(url: string, result: DominantDarkColor): void {
  colorCache.delete(url);
  colorCache.set(url, result);
  while (colorCache.size > MAX_COLOR_CACHE) {
    const oldest = colorCache.keys().next().value;
    if (oldest === undefined) break;
    colorCache.delete(oldest);
  }
}

export function useDominantDarkColor(
  imageUrl: string | undefined,
): DominantDarkColor | null {
  const [color, setColor] = useState<DominantDarkColor | null>(() => {
    if (!imageUrl) return null;
    return colorCache.get(imageUrl) ?? null;
  });

  useEffect(() => {
    if (!imageUrl) {
      setColor(null);
      return;
    }

    if (colorCache.has(imageUrl)) {
      setColor(colorCache.get(imageUrl)!);
      return;
    }

    let active = true;

    const processImage = (img: HTMLImageElement) => {
      const extracted = extractDarkDominantRgb(img);
      if (extracted && active) {
        const [r, g, b] = extracted;
        const result: DominantDarkColor = {
          r,
          g,
          b,
          rgb: `rgb(${r}, ${g}, ${b})`,
          rgba: (alpha: number) => `rgba(${r}, ${g}, ${b}, ${alpha})`,
        };
        rememberColor(imageUrl, result);
        setColor(result);
      }
    };

    void coverElementLoader(imageUrl).then((element) => {
      if (active && element) processImage(element);
    });

    return () => {
      active = false;
    };
  }, [imageUrl]);

  return color;
}

export default useDominantDarkColor;
