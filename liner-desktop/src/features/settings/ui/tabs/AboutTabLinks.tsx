import type { ReactNode } from "react";

interface AboutLink {
  id: string;
  url: string;
  label: string;
  icon: ReactNode;
}

const openUrl = (url: string) =>
  window.open(url, "_blank", "noopener,noreferrer");

export function AboutTabLinks() {
  const links: AboutLink[] = [];
  if (links.length === 0) return null;

  return (
    <div className="flex flex-wrap items-center gap-[8px]">
      {links.map((link) => (
        <button
          key={link.id}
          type="button"
          onClick={() => openUrl(link.url)}
          className="flex h-[38px] items-center gap-[8px] rounded-item border-0 bg-fill px-[14px] text-label font-[500] text-text-primary transition-colors hover:bg-fill-hover"
        >
          {link.icon}
          {link.label}
        </button>
      ))}
    </div>
  );
}
