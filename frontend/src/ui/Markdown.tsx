import ReactMarkdown, { type Components } from 'react-markdown';
import remarkGfm from 'remark-gfm';
import styles from './Markdown.module.css';

const SAFE_PROTOCOL = /^(https?:|mailto:|\/|#)/i;

const components: Components = {
  a: ({ href, children }) => {
    const safe = href && SAFE_PROTOCOL.test(href) ? href : undefined;
    const external = safe?.startsWith('http');
    return (
      <a href={safe} target={external ? '_blank' : undefined} rel={external ? 'noopener noreferrer nofollow' : undefined}>
        {children}
      </a>
    );
  },
  // Images in user content could be used for tracking; show them as links instead.
  img: ({ src, alt }) => {
    const url = typeof src === 'string' && SAFE_PROTOCOL.test(src) ? src : undefined;
    return url ? (
      <a href={url} target="_blank" rel="noopener noreferrer nofollow">
        {alt ?? 'image'}
      </a>
    ) : null;
  },
};

/**
 * Renders user-written Markdown (GitHub flavour). Raw HTML is never interpreted (it is
 * shown as text) and link protocols are restricted.
 */
export function Markdown({ source }: { source: string }) {
  return (
    <div className={styles.markdown}>
      <ReactMarkdown remarkPlugins={[remarkGfm]} components={components}>
        {source}
      </ReactMarkdown>
    </div>
  );
}
