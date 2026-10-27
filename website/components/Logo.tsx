import type { SVGProps } from 'react';
import { mark, wordmark } from '@/lib/logo-paths';

interface LogoProps extends SVGProps<SVGSVGElement> {
  height?: number;
}

/** Official Jtrac wordmark (path data from logo.svg; same component as the product UI). */
export function Logo({ height = 26, className, style, ...rest }: LogoProps) {
  return (
    <svg
      className={`block shrink-0 ${className ?? ''}`}
      height={height}
      viewBox={wordmark.viewBox}
      style={{ aspectRatio: '16900 / 3950', ...style }}
      role="img"
      aria-label="Jtrac"
      focusable="false"
      {...rest}
    >
      <g className="fill-(--color-text)">
        {wordmark.base.map((d) => (
          <path key={d.slice(0, 24)} d={d} />
        ))}
      </g>
      <g className="fill-(--color-accent)">
        {wordmark.accent.map((d) => (
          <path key={d.slice(0, 24)} d={d} />
        ))}
      </g>
    </svg>
  );
}

/** Official square Jtrac mark (path data from logo(2).svg). */
export function LogoMark({ size = 28, className, style, ...rest }: { size?: number } & SVGProps<SVGSVGElement>) {
  return (
    <svg
      className={`block shrink-0 ${className ?? ''}`}
      width={size}
      height={size}
      viewBox={mark.viewBox}
      role="img"
      aria-label="Jtrac Mark"
      focusable="false"
      style={{ ...style }}
      {...rest}
    >
      <path
        className="fill-(--color-text)"
        d={mark.base}
      />
      <path
        className="fill-(--color-accent)"
        d={mark.accent}
      />
    </svg>
  );
}
