import { useId, useState } from 'react';
import type { Bucket, MilestoneProgress } from '../../api/types';
import { formatDate, formatShortDate } from '../../lib/format';
import styles from './charts.module.css';

/** Rounds a maximum up to a clean axis value (1, 2, 5 × 10^n). */
export function niceMax(value: number): number {
  if (value <= 0) {
    return 1;
  }
  const power = 10 ** Math.floor(Math.log10(value));
  const fraction = value / power;
  const nice = fraction <= 1 ? 1 : fraction <= 2 ? 2 : fraction <= 5 ? 5 : 10;
  return nice * power;
}

function compact(value: number): string {
  return value >= 1000 ? `${String(Math.round(value / 100) / 10)}K` : String(value);
}

/** A table view of the same data, for screen readers and exact values. */
function DataTable({ caption, headers, rows }: { caption: string; headers: string[]; rows: (string | number)[][] }) {
  return (
    <table className={styles.table}>
      <caption className="visually-hidden">{caption}</caption>
      <thead>
        <tr>
          {headers.map((h) => (
            <th key={h} scope="col">
              {h}
            </th>
          ))}
        </tr>
      </thead>
      <tbody>
        {rows.map((row, i) => (
          <tr key={i}>
            {row.map((cell, j) => (j === 0 ? <th key={j} scope="row">{cell}</th> : <td key={j}>{cell}</td>))}
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function ViewToggle({ table, onChange }: { table: boolean; onChange: (table: boolean) => void }) {
  return (
    <button type="button" className={styles.toggle} aria-pressed={table} onClick={() => { onChange(!table); }}>
      {table ? 'Show chart' : 'Show table'}
    </button>
  );
}

/** Horizontal bars, one series: magnitude per group, value at the bar tip. */
export function BreakdownChart({ title, buckets }: { title: string; buckets: Bucket[] }) {
  const [table, setTable] = useState(false);
  const max = niceMax(Math.max(0, ...buckets.map((b) => b.count)));
  if (buckets.length === 0) {
    return <p className="muted">No matching issues.</p>;
  }
  return (
    <figure className={styles.figure}>
      <ViewToggle table={table} onChange={setTable} />
      {table ? (
        <DataTable caption={title} headers={['Group', 'Issues']} rows={buckets.map((b) => [b.label, b.count])} />
      ) : (
        <ul className={styles.bars} aria-label={title}>
          {buckets.map((b) => (
            <li key={b.key ?? 'none'} className={styles.barRow} title={`${b.label}: ${String(b.count)}`}>
              <span className={styles.barLabel}>{b.label}</span>
              <span className={styles.barTrack}>
                <span className={styles.bar} style={{ inlineSize: `${String((b.count / max) * 100)}%` }} />
                <span className={styles.barValue}>{b.count}</span>
              </span>
            </li>
          ))}
        </ul>
      )}
    </figure>
  );
}

interface Day {
  day: string;
  created: number;
  resolved: number;
}

const W = 600;
const H = 220;
const PAD = { top: 12, right: 64, bottom: 28, left: 36 };

/** Created vs resolved per day: two 2px lines on one axis, legend + end labels, crosshair tooltip. */
export function TrendChart({ title, days }: { title: string; days: Day[] }) {
  const [table, setTable] = useState(false);
  const [hover, setHover] = useState<number | null>(null);
  const id = useId();
  const max = niceMax(Math.max(1, ...days.map((d) => Math.max(d.created, d.resolved))));
  const x = (i: number) => PAD.left + (i * (W - PAD.left - PAD.right)) / Math.max(1, days.length - 1);
  const y = (v: number) => PAD.top + (1 - v / max) * (H - PAD.top - PAD.bottom);
  const path = (key: 'created' | 'resolved') => days.map((d, i) => `${i === 0 ? 'M' : 'L'}${x(i).toFixed(1)},${y(d[key]).toFixed(1)}`).join(' ');
  const ticks = [0, max / 2, max];
  const last = days.length - 1;
  const totals = days.reduce((acc, d) => ({ created: acc.created + d.created, resolved: acc.resolved + d.resolved }), { created: 0, resolved: 0 });
  const hovered = hover === null ? undefined : days[hover];

  return (
    <figure className={styles.figure}>
      <div className={styles.legend}>
        <span>
          <span className={styles.key} data-series="1" aria-hidden="true" /> Created ({totals.created})
        </span>
        <span>
          <span className={styles.key} data-series="2" aria-hidden="true" /> Resolved ({totals.resolved})
        </span>
        <ViewToggle table={table} onChange={setTable} />
      </div>
      {table ? (
        <DataTable caption={title} headers={['Day', 'Created', 'Resolved']} rows={days.map((d) => [formatDate(d.day), d.created, d.resolved])} />
      ) : (
        <div className={styles.plot}>
          <svg
            viewBox={`0 0 ${String(W)} ${String(H)}`}
            className={styles.svg}
            role="img"
            aria-labelledby={`${id}-desc`}
            onPointerLeave={() => { setHover(null); }}
            onPointerMove={(event) => {
              const rect = event.currentTarget.getBoundingClientRect();
              const px = ((event.clientX - rect.left) / rect.width) * W;
              const index = Math.round(((px - PAD.left) / (W - PAD.left - PAD.right)) * last);
              setHover(Math.max(0, Math.min(last, index)));
            }}
          >
            <desc id={`${id}-desc`}>
              {title}: {totals.created} created and {totals.resolved} resolved over {days.length} days.
            </desc>
            {ticks.map((t) => (
              <g key={t}>
                <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)} className={styles.grid} />
                <text x={PAD.left - 6} y={y(t)} className={styles.tick} textAnchor="end" dominantBaseline="middle">
                  {compact(t)}
                </text>
              </g>
            ))}
            {days.length > 0 && (
              <>
                <text x={x(0)} y={H - 8} className={styles.tick} textAnchor="start">
                  {formatShortDate(days[0]?.day)}
                </text>
                <text x={x(last)} y={H - 8} className={styles.tick} textAnchor="end">
                  {formatShortDate(days[last]?.day)}
                </text>
              </>
            )}
            <path d={path('created')} className={styles.line} data-series="1" />
            <path d={path('resolved')} className={styles.line} data-series="2" />
            {days.length > 0 && (
              <>
                <circle cx={x(last)} cy={y(days[last]?.created ?? 0)} r={4} className={styles.dot} data-series="1" />
                <circle cx={x(last)} cy={y(days[last]?.resolved ?? 0)} r={4} className={styles.dot} data-series="2" />
                <text x={x(last) + 8} y={y(days[last]?.created ?? 0)} className={styles.endLabel} dominantBaseline="middle">
                  Created
                </text>
                <text x={x(last) + 8} y={y(days[last]?.resolved ?? 0) + (days[last]?.resolved === days[last]?.created ? 12 : 0)} className={styles.endLabel} dominantBaseline="middle">
                  Resolved
                </text>
              </>
            )}
            {hovered && hover !== null && (
              <g>
                <line x1={x(hover)} x2={x(hover)} y1={PAD.top} y2={H - PAD.bottom} className={styles.crosshair} />
                <circle cx={x(hover)} cy={y(hovered.created)} r={4} className={styles.dot} data-series="1" />
                <circle cx={x(hover)} cy={y(hovered.resolved)} r={4} className={styles.dot} data-series="2" />
              </g>
            )}
          </svg>
          {hovered && hover !== null && (
            <div className={styles.tooltip} style={{ insetInlineStart: `${String((x(hover) / W) * 100)}%` }} aria-hidden="true">
              <strong>{formatDate(hovered.day)}</strong>
              <span>
                <span className={styles.key} data-series="1" /> Created {hovered.created}
              </span>
              <span>
                <span className={styles.key} data-series="2" /> Resolved {hovered.resolved}
              </span>
            </div>
          )}
        </div>
      )}
    </figure>
  );
}

/** One meter per milestone: done / (total − canceled). */
export function MilestoneMeters({ milestones }: { milestones: MilestoneProgress[] }) {
  if (milestones.length === 0) {
    return <p className="muted">This project has no milestones.</p>;
  }
  return (
    <ul className={styles.meters}>
      {milestones.map((m) => {
        const pct = m.progress === null || m.progress === undefined ? null : Math.round(m.progress * 100);
        return (
          <li key={m.id} className={styles.meterRow}>
            <div className={styles.meterHead}>
              <strong>{m.name}</strong>
              <span className="muted">
                {pct === null ? 'No issues' : `${String(pct)}% · ${String(m.done)} of ${String(m.total - m.canceled)} done`}
                {m.dueDate ? ` · due ${formatDate(m.dueDate)}` : ''}
              </span>
            </div>
            <div
              className={styles.meter}
              role="meter"
              aria-label={`${m.name} progress`}
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={pct ?? 0}
              aria-valuetext={pct === null ? 'No countable issues' : `${String(pct)} percent`}
            >
              <span style={{ inlineSize: `${String(pct ?? 0)}%` }} />
            </div>
          </li>
        );
      })}
    </ul>
  );
}
