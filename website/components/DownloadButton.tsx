'use client';

import { Download as DownloadIcon } from 'lucide-react';
import { usePathname } from 'next/navigation';
import { useEffect, useState } from 'react';
import { type Download, type DownloadId, downloads } from '@/lib/site';
import { ButtonLink } from './ui';

interface NavigatorUAData {
  platform?: string;
  getHighEntropyValues?: (hints: string[]) => Promise<{ architecture?: string; platform?: string }>;
}

/**
 * Best guess of the visitor's package. Returns null when unsure (mobile, unknown OS, or a Mac
 * whose CPU cannot be read: Safari reports every Mac as Intel), so the caller falls back to
 * the download table instead of serving the wrong archive.
 */
export async function detectDownload(): Promise<Download | null> {
  const nav = navigator as Navigator & { userAgentData?: NavigatorUAData };
  const ua = navigator.userAgent;
  const find = (id: DownloadId) => downloads.find((d) => d.id === id) ?? null;
  if (/Android|iPhone|iPad|iPod/i.test(ua)) return null;
  const hints = await nav.userAgentData?.getHighEntropyValues?.(['architecture', 'platform']).catch(() => undefined);
  const platform = hints?.platform ?? nav.userAgentData?.platform ?? '';
  const arch = hints?.architecture;
  if (/Windows/i.test(platform) || /Windows NT/i.test(ua)) return arch === 'arm' ? null : find('windows-x64');
  if (/macOS/i.test(platform) || /Macintosh/i.test(ua)) {
    if (arch === 'arm') return find('macos-arm64');
    if (arch === 'x86') return find('macos-x64');
    return null;
  }
  if (/Linux/i.test(platform) || /Linux/i.test(ua)) {
    if (arch === 'arm' || /aarch64|arm64/i.test(ua)) return find('linux-arm64');
    if (arch === 'x86' || /x86_64|amd64/i.test(ua)) return find('linux-x64');
  }
  return null;
}

/** Hook form of detectDownload; null until detection finishes or when unsure. */
export function useDetectedDownload() {
  const [detected, setDetected] = useState<Download | null>(null);
  useEffect(() => {
    let active = true;
    void detectDownload().then((d) => {
      if (active) setDetected(d);
    });
    return () => {
      active = false;
    };
  }, []);
  return detected;
}

/**
 * "Download Jtrac": a direct download of the visitor's package when it can be detected,
 * otherwise the download table (on the home page) or the download page. Never a detour through the Releases page.
 */
export function DownloadButton({ size = 'lg', className }: { size?: 'sm' | 'lg'; className?: string }) {
  const detected = useDetectedDownload();
  const pathname = usePathname();
  const icon = <DownloadIcon size={size === 'sm' ? 16 : 17} aria-hidden="true" />;
  if (!detected) {
    return (
      <ButtonLink href={pathname === '/' ? '#download' : '/download/'} size={size} icon={icon} className={className}>
        Download Jtrac
      </ButtonLink>
    );
  }
  return (
    <ButtonLink href={detected.url} download={`${String(detected.sizeMb)} MB`} size={size} icon={icon} className={className}>
      {size === 'sm' ? 'Download Jtrac' : `Download for ${detected.os}`}
    </ButtonLink>
  );
}
