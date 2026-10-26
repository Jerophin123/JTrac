import { useState, type CSSProperties } from 'react';
import { readableTextOn } from '../lib/contrast';
import styles from './OrgMark.module.css';

type Size = 'xs' | 'sm' | 'md' | 'lg';

interface OrgMarkProps {
  name: string;
  logoUrl?: string | null | undefined;
  color?: string | null | undefined;
  size?: Size;
  /** Accessible name for the logo; decorative (empty alt) by default. */
  alt?: string;
}

/**
 * An organization's logo, or its initial on the brand color. The initial's color is picked
 * for contrast against the brand color, so any brand color stays legible.
 */
export function OrgMark({ name, logoUrl, color, size = 'sm', alt = '' }: OrgMarkProps) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null);
  const className = `${styles.mark ?? ''} ${styles[size] ?? ''}`;
  if (logoUrl && failedUrl !== logoUrl) {
    return (
      <img
        src={logoUrl}
        alt={alt}
        className={`${className} ${styles.logo ?? ''}`}
        onError={() => {
          setFailedUrl(logoUrl);
        }}
      />
    );
  }
  const style: CSSProperties | undefined = color ? { backgroundColor: color, color: readableTextOn(color) } : undefined;
  return (
    <span className={className} style={style} aria-hidden={alt ? undefined : true} role={alt ? 'img' : undefined} aria-label={alt || undefined}>
      {size === 'xs' ? null : name.trim().charAt(0).toUpperCase() || 'O'}
    </span>
  );
}
