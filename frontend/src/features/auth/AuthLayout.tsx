import { Link } from '@tanstack/react-router';
import { Bell, ShieldCheck, SquareKanban, Workflow, type LucideIcon } from 'lucide-react';
import type { ReactNode } from 'react';
import { ThemeSwitcher } from '../../app/shell/ThemeSwitcher';
import { Logo } from '../../ui/Logo';
import styles from './LoginPage.module.css';

interface AuthLayoutProps {
  title: string;
  subtitle?: ReactNode;
  children: ReactNode;
  footer?: ReactNode;
  note?: ReactNode;
  /** A short, friendly line above the title (for example a time-of-day greeting). */
  greeting?: ReactNode;
  /** A wider card for longer forms such as first-run setup. */
  wide?: boolean;
  /** Replaces the default showcase copy, for screens with a purpose of their own. */
  showcase?: ShowcaseContent;
}

export interface ShowcasePoint {
  Icon: LucideIcon;
  text: string;
}

export interface ShowcaseContent {
  tagline: ReactNode;
  lede: string;
  points: ShowcasePoint[];
}

/** What JTRAC does, in plain words. Every point describes a feature that exists today. */
const POINTS: ShowcasePoint[] = [
  { Icon: SquareKanban, text: 'Plan work on boards, lists and timelines' },
  { Icon: Workflow, text: 'Workflows shaped around how your team works' },
  { Icon: Bell, text: 'Mentions and an inbox keep everyone in the loop' },
  { Icon: ShieldCheck, text: 'Everyone sees exactly what they should' },
];

const DEFAULT_SHOWCASE: ShowcaseContent = {
  tagline: (
    <>
      Every issue, from first report to <em>done</em>.
    </>
  ),
  lede: 'One calm place for your team to plan, track and ship work together.',
  points: POINTS,
};

/** The centered card used by every signed-out screen. */
export function AuthLayout({ title, subtitle, children, footer, note, greeting, wide = false, showcase = DEFAULT_SHOWCASE }: AuthLayoutProps) {
  return (
    <div className={styles.page}>
      <div className={styles.formSide}>
        <main className={styles.panel} data-wide={wide || undefined} aria-labelledby="auth-title">
          <div className={styles.brand}>
            <Logo height={32} />
          </div>
          <header className={styles.header}>
            {greeting && <p className={styles.greeting}>{greeting}</p>}
            <h1 id="auth-title" className={styles.title}>
              {title}
            </h1>
            {subtitle && <p className={styles.subtitle}>{subtitle}</p>}
          </header>
          {children}
          {footer && <div className={styles.links}>{footer}</div>}
          {note && <p className={styles.footnote}>{note}</p>}
        </main>
        <nav className={styles.legal} aria-label="Legal">
          <Link to="/legal/terms">Terms</Link>
          <Link to="/legal/privacy">Privacy</Link>
          <Link to="/legal/grievance">Grievances</Link>
        </nav>
        {/* After the form in the DOM so keyboard users reach the form first; placed in the corner by CSS. */}
        <div className={styles.corner}>
          <ThemeSwitcher />
        </div>
      </div>
      <aside className={styles.showcase} aria-label="About JTRAC">
        <div className={`${styles.bars ?? ''} ${styles.barsTop ?? ''}`} aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
        <div className={styles.showcaseInner}>
          <Logo height={44} />
          <p className={styles.welcome}>Welcome to JTRAC</p>
          <p className={styles.tagline}>{showcase.tagline}</p>
          <p className={styles.lede}>{showcase.lede}</p>
          <ul className={styles.points}>
            {showcase.points.map(({ Icon, text }) => (
              <li key={text}>
                <span className={styles.pointIcon} aria-hidden="true">
                  <Icon size={16} />
                </span>
                {text}
              </li>
            ))}
          </ul>
        </div>
        <div className={styles.bars} aria-hidden="true">
          <span />
          <span />
          <span />
        </div>
      </aside>
    </div>
  );
}

export const authFormClass = styles.form;
