'use client';

import { useEffect, useRef, useState, useSyncExternalStore } from 'react';

const VIDEO_SRC =
  'https://d8j0ntlcm91z4.cloudfront.net/user_38xzZboKViGWJOttwIXH07lWA1P/hf_20260508_064122_c4750c0e-7476-4b44-94a2-a85a65c63bf2.mp4';

// The ~16 MB video is only fetched on wide screens, without reduced motion or Save-Data.
const VIDEO_QUERY = '(min-width: 768px) and (prefers-reduced-motion: no-preference)';

function subscribe(onChange: () => void) {
  const mq = window.matchMedia(VIDEO_QUERY);
  mq.addEventListener('change', onChange);
  return () => mq.removeEventListener('change', onChange);
}

function videoAllowed() {
  const connection = (navigator as Navigator & { connection?: { saveData?: boolean } }).connection;
  return window.matchMedia(VIDEO_QUERY).matches && connection?.saveData !== true;
}

/** Cinematic hero backdrop: CSS atmosphere always, muted looping video when appropriate. */
export function HeroBackdrop() {
  const allowed = useSyncExternalStore(subscribe, videoAllowed, () => false);
  const [ready, setReady] = useState(false);
  const [failed, setFailed] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  // Pause the video while the hero is off screen.
  useEffect(() => {
    const video = videoRef.current;
    if (!allowed || !video) return;
    const observer = new IntersectionObserver(([entry]) => {
      if (entry?.isIntersecting) void video.play().catch(() => undefined);
      else video.pause();
    });
    observer.observe(video);
    return () => observer.disconnect();
  }, [allowed]);

  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10 overflow-hidden">
      {/* CSS fallback atmosphere: always present beneath the video. */}
      <div className="absolute inset-0 bg-[radial-gradient(120%_80%_at_50%_-10%,var(--color-surface-muted)_0%,var(--color-surface-sunken)_55%)]" />
      <div className="absolute start-1/2 top-[-18rem] h-[42rem] w-[70rem] max-w-[160vw] -translate-x-1/2 rounded-full bg-[radial-gradient(closest-side,rgb(252_111_8/0.14),rgb(252_111_8/0.04)_45%,transparent_72%)] blur-2xl rtl:translate-x-1/2" />
      <div className="absolute end-[-10rem] top-[30%] h-[28rem] w-[28rem] rounded-full bg-[radial-gradient(closest-side,rgb(252_111_8/0.09),transparent)] blur-3xl" />
      <div className="absolute inset-0 bg-[linear-gradient(rgb(255_255_255/0.025)_1px,transparent_1px),linear-gradient(90deg,rgb(255_255_255/0.025)_1px,transparent_1px)] bg-[size:72px_72px] [mask-image:radial-gradient(70%_60%_at_50%_30%,#000_20%,transparent_75%)]" />

      {allowed && !failed ? (
        // Isolated layer: the footage is desaturated, then re-tinted with brand orange.
        // Masked to the edges: the footage frames the headline instead of running behind it.
        <div
          className={`absolute inset-x-0 top-0 h-[100svh] isolate transition-opacity duration-[1600ms] [mask-image:radial-gradient(ellipse_62%_58%_at_50%_42%,transparent_30%,black_78%)] ${ready ? 'opacity-55' : 'opacity-0'}`}
        >
          <video
            ref={videoRef}
            className="absolute inset-0 h-full w-full object-cover [filter:grayscale(1)_contrast(1.15)_brightness(0.9)]"
            src={VIDEO_SRC}
            autoPlay
            muted
            loop
            playsInline
            preload="auto"
            disablePictureInPicture
            tabIndex={-1}
            onCanPlay={() => setReady(true)}
            onError={() => setFailed(true)}
          />
          <div className="absolute inset-0 bg-[linear-gradient(160deg,var(--color-accent)_0%,#7a3404_55%,var(--color-surface)_100%)] opacity-55 mix-blend-color" />
        </div>
      ) : null}

      {/* Readability overlay and a soft fade into the next section. */}
      <div className="absolute inset-0 bg-[linear-gradient(180deg,rgb(17_15_13/0.55)_0%,rgb(17_15_13/0.35)_40%,rgb(17_15_13/0.85)_80%,var(--color-surface-sunken)_100%)]" />
      <div className="absolute inset-0 bg-[radial-gradient(60%_50%_at_50%_45%,transparent,rgb(17_15_13/0.6))]" />
    </div>
  );
}
