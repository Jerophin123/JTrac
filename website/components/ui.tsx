import { ArrowUpRight } from 'lucide-react';
import type { ReactNode } from 'react';
import landing from './landing.module.css';
import { SpeedMark, Words } from './Reveal';
import buttonStyles from './product/styles/Button.module.css';

type Variant = 'primary' | 'secondary' | 'ghost';

interface ButtonLinkProps {
  href: string;
  children: ReactNode;
  variant?: Variant;
  size?: 'sm' | 'md' | 'lg';
  icon?: ReactNode;
  className?: string;
  /** Direct file download: stays in the tab; the value (e.g. "153 MB") is announced. */
  download?: string;
}

/**
 * A link styled as the product's slanted Button (ui/Button.module.css): the fill leans like
 * the logo's strokes while the label stays upright. GitHub pages open in a new tab; direct
 * downloads stay in the tab.
 */
export function ButtonLink({ href, children, variant = 'primary', size = 'lg', icon, className = '', download }: ButtonLinkProps) {
  const external = href.startsWith('http') && download === undefined;
  const sizeClass = size === 'lg' ? `${buttonStyles.md} ${landing.lg}` : buttonStyles[size];
  const variantClass =
    variant === 'primary'
      ? `${buttonStyles.primary} ${size === 'sm' ? '' : landing.glow}`
      : variant === 'secondary'
        ? `${buttonStyles.secondary} ${landing.glassButton}`
        : buttonStyles.ghost;
  return (
    <a
      href={href}
      {...(external ? { target: '_blank', rel: 'noopener noreferrer' } : {})}
      className={`group ${buttonStyles.button} ${variantClass} ${sizeClass} no-underline ${className}`}
    >
      {icon}
      <span className={buttonStyles.label}>{children}</span>
      {download !== undefined ? <span className="sr-only">(direct download, {download})</span> : null}
      {external ? (
        <>
          <ArrowUpRight
            size={15}
            aria-hidden="true"
            className="-me-0.5 opacity-70 transition-transform duration-200 group-hover:translate-x-0.5 group-hover:-translate-y-0.5"
          />
          <span className="sr-only">(opens on GitHub in a new tab)</span>
        </>
      ) : null}
    </a>
  );
}

interface SectionHeaderProps {
  id: string;
  eyebrow: string;
  title: string;
  lede?: ReactNode;
  align?: 'center' | 'start';
}

/**
 * Section heading with the product's page-title speed strokes (ui/Page.module.css): the
 * strokes draw in and the title rises word by word when the section scrolls into view.
 */
export function SectionHeader({ id, eyebrow, title, lede, align = 'center' }: SectionHeaderProps) {
  const centered = align === 'center';
  return (
    <div className={`max-w-2xl ${centered ? 'mx-auto text-center' : ''}`}>
      <SpeedMark centered={centered} />
      <p className="eyebrow mt-5">{eyebrow}</p>
      <h2 id={id} aria-label={title} className="mt-3 text-3xl font-bold tracking-[-0.03em] text-balance sm:text-4xl md:text-[2.75rem] md:leading-[1.08]">
        <Words text={title} inView delay={0.1} stagger={0.05} wordClassName="text-sheen" />
      </h2>
      {lede ? <p className="mt-5 text-base leading-relaxed text-muted text-pretty sm:text-lg">{lede}</p> : null}
    </div>
  );
}
